import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '../../../../shared/api/apiError'
import type { ConsumerWaitingSnapshot } from '../api/queries'
import {
  WAITING_STATUS_LABEL,
  WAITING_STATUS_TONE,
  canCancelWaiting,
  formatBusinessDate,
  isWaitingCancellable,
  isWaitingClosed,
  toWaitingCancelError,
} from './currentWaitingView'

function snapshot(
  overrides: Partial<ConsumerWaitingSnapshot> = {},
): ConsumerWaitingSnapshot {
  return {
    waitingTeamId: 'team-410',
    storeId: 'store-1',
    businessDate: '2026-08-20',
    status: 'WAITING',
    queueSequence: 7,
    teamsAhead: 3,
    partySize: 2,
    createdAt: '2026-08-20T02:00:00+09:00',
    calledAt: null,
    arrivalDeadline: null,
    arrivedAt: null,
    cancelledAt: null,
    version: 4,
    memberships: [
      {
        membershipId: 'membership-1',
        role: 'REPRESENTATIVE',
        joinedAt: '2026-08-20T02:00:00+09:00',
        self: true,
      },
    ],
    ...overrides,
  }
}

describe('현재 웨이팅 상태 표시', () => {
  it('계약의 모든 상태에 문구와 뱃지 색을 준다', () => {
    const statuses: ConsumerWaitingSnapshot['status'][] = [
      'WAITING',
      'CALLED',
      'ARRIVED',
      'CHECKED_IN',
      'CANCELLED',
      'NO_SHOW',
      'CLOSED_BY_STORE',
      'RESERVATION_CONVERTING',
      'RESERVATION_CONVERTED',
    ]

    for (const status of statuses) {
      expect(WAITING_STATUS_LABEL[status]).toBeTruthy()
      expect(WAITING_STATUS_TONE[status]).toBeTruthy()
    }
  })

  it('진행 중 상태와 종료 상태를 구분한다', () => {
    expect(isWaitingClosed('WAITING')).toBe(false)
    expect(isWaitingClosed('CALLED')).toBe(false)
    expect(isWaitingClosed('ARRIVED')).toBe(false)
    expect(isWaitingClosed('RESERVATION_CONVERTING')).toBe(false)

    expect(isWaitingClosed('CHECKED_IN')).toBe(true)
    expect(isWaitingClosed('CANCELLED')).toBe(true)
    expect(isWaitingClosed('NO_SHOW')).toBe(true)
    expect(isWaitingClosed('CLOSED_BY_STORE')).toBe(true)
    expect(isWaitingClosed('RESERVATION_CONVERTED')).toBe(true)
  })
})

describe('취소 가능 여부', () => {
  it('백엔드가 취소 전이를 허용하는 상태만 취소 가능으로 본다', () => {
    expect(isWaitingCancellable('WAITING')).toBe(true)
    expect(isWaitingCancellable('CALLED')).toBe(true)
    expect(isWaitingCancellable('ARRIVED')).toBe(true)
    expect(isWaitingCancellable('RESERVATION_CONVERTING')).toBe(true)

    expect(isWaitingCancellable('CHECKED_IN')).toBe(false)
    expect(isWaitingCancellable('CANCELLED')).toBe(false)
    expect(isWaitingCancellable('NO_SHOW')).toBe(false)
    expect(isWaitingCancellable('CLOSED_BY_STORE')).toBe(false)
    expect(isWaitingCancellable('RESERVATION_CONVERTED')).toBe(false)
  })

  it('대표자만 팀 취소를 할 수 있다', () => {
    expect(canCancelWaiting(snapshot())).toBe(true)
  })

  it('초대로 합류한 일행에게는 취소를 허용하지 않는다', () => {
    const asMember = snapshot({
      memberships: [
        {
          membershipId: 'membership-1',
          role: 'REPRESENTATIVE',
          joinedAt: '2026-08-20T02:00:00+09:00',
          self: false,
        },
        {
          membershipId: 'membership-2',
          role: 'MEMBER',
          joinedAt: '2026-08-20T02:10:00+09:00',
          self: true,
        },
      ],
    })

    expect(canCancelWaiting(asMember)).toBe(false)
  })

  it('종료된 웨이팅은 대표자여도 취소하지 않는다', () => {
    expect(canCancelWaiting(snapshot({ status: 'CHECKED_IN' }))).toBe(false)
  })

  it('본인 membership이 없으면 취소하지 않는다', () => {
    expect(canCancelWaiting(snapshot({ memberships: [] }))).toBe(false)
  })
})

describe('취소 실패 판정', () => {
  it('네트워크 실패는 결과 불명으로 다룬다', () => {
    expect(toWaitingCancelError(new NetworkError('offline'))).toEqual({
      code: 'OUTCOME_UNKNOWN',
    })
  })

  it('5xx는 결과 불명으로 다룬다', () => {
    const error = new ApiError({
      status: 503,
      code: 'COMMON_009',
      message: '일시적인 오류입니다.',
    })

    expect(toWaitingCancelError(error)).toEqual({ code: 'OUTCOME_UNKNOWN' })
  })

  it('멱등 키 재사용은 결과 불명으로 다룬다', () => {
    const error = new ApiError({
      status: 409,
      code: 'COMMON_007',
      message: '동일한 Idempotency-Key를 다른 요청에 사용할 수 없습니다.',
    })

    expect(toWaitingCancelError(error)).toEqual({ code: 'OUTCOME_UNKNOWN' })
  })

  it('계약의 취소 충돌 code를 각각 다른 복구 경로로 옮긴다', () => {
    const cases: [string, number, string][] = [
      ['WAITING_005', 409, 'VERSION_CONFLICT'],
      ['WAITING_006', 409, 'INVALID_TRANSITION'],
      ['WAITING_008', 409, 'MEMBERSHIP_CONFLICT'],
      ['WAITING_003', 404, 'NOT_FOUND'],
      ['AUTH_011', 403, 'FORBIDDEN'],
    ]

    for (const [code, status, expected] of cases) {
      const error = new ApiError({ status, code, message: `${code} 실패` })
      expect(toWaitingCancelError(error)).toEqual({
        code: expected,
        message: `${code} 실패`,
      })
    }
  })

  it('모르는 code는 status로 판정하고 없으면 일반 실패로 둔다', () => {
    const forbidden = new ApiError({
      status: 403,
      code: 'STORE_015',
      message: '제한된 기능입니다.',
    })
    expect(toWaitingCancelError(forbidden).code).toBe('FORBIDDEN')

    const badRequest = new ApiError({
      status: 400,
      code: 'COMMON_001',
      message: '잘못된 요청입니다.',
    })
    expect(toWaitingCancelError(badRequest).code).toBe('REQUEST_FAILED')
  })
})

describe('영업일 표시', () => {
  it('date 값을 사람이 읽는 날짜로 옮긴다', () => {
    expect(formatBusinessDate('2026-08-20')).toContain('2026')
  })

  it('해석할 수 없는 값은 null로 둔다', () => {
    expect(formatBusinessDate('not-a-date')).toBeNull()
  })
})
