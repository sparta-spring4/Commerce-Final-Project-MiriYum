import { describe, expect, test } from 'vitest'
import { allowedCommands, isQueueHead, isTerminal } from './transitions'
import { TEAM_STATUSES, type WaitingTeamStatus } from './types'

/**
 * 이 표가 화면이 여는 버튼을 정한다. 넓게 잡으면 서버가 거절할 행동을 운영자에게
 * 할 수 있다고 약속하게 된다.
 */
describe('상태별 허용 명령', () => {
  test('대기 중은 호출과 취소', () => {
    expect(allowedCommands('WAITING')).toEqual(['call', 'cancel'])
  })

  test('호출됨은 도착 확인과 취소', () => {
    expect(allowedCommands('CALLED')).toEqual(['arrive', 'cancel'])
  })

  test('도착은 입장 처리와 취소', () => {
    expect(allowedCommands('ARRIVED')).toEqual(['check-in', 'cancel'])
  })

  test('예약 전환 중은 취소만 허용한다', () => {
    // 계약이 비종결 상태지만 운영자 cancel만 허용한다고 명시한다.
    expect(allowedCommands('RESERVATION_CONVERTING')).toEqual(['cancel'])
  })

  test.each<WaitingTeamStatus>([
    'CHECKED_IN',
    'CANCELLED',
    'NO_SHOW',
    'CLOSED_BY_STORE',
    'RESERVATION_CONVERTED',
  ])('종결 상태 %s 에서는 어떤 명령도 열지 않는다', (status) => {
    expect(allowedCommands(status)).toEqual([])
    expect(isTerminal(status)).toBe(true)
  })

  test('계약의 모든 상태를 빠짐없이 판정한다', () => {
    // 상태가 추가되면 이 테스트가 먼저 깨져 표를 갱신하게 만든다.
    for (const status of TEAM_STATUSES) {
      expect(Array.isArray(allowedCommands(status))).toBe(true)
    }
  })
})

describe('FIFO 선두 판정', () => {
  test('선두 ID와 같아야 호출을 연다', () => {
    expect(isQueueHead('11', '11')).toBe(true)
    expect(isQueueHead('11', '12')).toBe(false)
  })

  test('선두를 모르면 열지 않는다', () => {
    // 모르는 상태에서 열면 서버가 WAITING_007로 거절할 행동을 약속하게 된다.
    expect(isQueueHead('11', null)).toBe(false)
  })
})
