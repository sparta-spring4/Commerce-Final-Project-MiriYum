import { isApiError } from '../../../../shared/api/apiError'
import { isOutcomeUnknown } from '../../../../shared/api/idempotencyKey'
import type { BadgeTone } from '../../../../shared/ui/Badge'
import type { ConsumerWaitingSnapshot } from '../api/queries'
import type { WaitingPartyGuidance, WaitingTeamStatus } from './partyViewState'

/**
 * 현재 웨이팅 상세 화면의 판정 규칙.
 *
 * 상태 문구·취소 가능 여부·오류 복구 경로를 한곳에 모은다. 화면 컴포넌트가
 * 상태 enum을 직접 비교하기 시작하면 같은 규칙이 여러 곳에서 조금씩 달라진다.
 */

/** 소비자에게 보이는 상태 문구. 운영자 화면의 문구와 어휘가 다르다. */
export const WAITING_STATUS_LABEL: Record<WaitingTeamStatus, string> = {
  WAITING: '대기 중',
  CALLED: '입장 호출',
  ARRIVED: '도착 확인',
  CHECKED_IN: '입장 완료',
  CANCELLED: '취소됨',
  NO_SHOW: '미도착 종료',
  CLOSED_BY_STORE: '매장 종료',
  RESERVATION_CONVERTING: '예약 전환 중',
  RESERVATION_CONVERTED: '예약 전환 완료',
}

export const WAITING_STATUS_TONE: Record<WaitingTeamStatus, BadgeTone> = {
  WAITING: 'neutral',
  CALLED: 'attention',
  ARRIVED: 'positive',
  CHECKED_IN: 'positive',
  CANCELLED: 'negative',
  NO_SHOW: 'negative',
  CLOSED_BY_STORE: 'negative',
  RESERVATION_CONVERTING: 'attention',
  RESERVATION_CONVERTED: 'positive',
}

/**
 * 상태별로 사용자가 지금 무엇을 하면 되는지.
 *
 * 예상 대기시간이나 입장 시각을 만들어 쓰지 않는다. 계약에 없는 값이고,
 * 확정 시각처럼 읽히면 안 된다.
 */
export const WAITING_STATUS_GUIDE: Record<WaitingTeamStatus, string> = {
  WAITING: '앞 팀이 줄어들면 이 화면에서 순번이 갱신됩니다. 호출 알림을 놓치지 않도록 매장 근처에 있어 주세요.',
  CALLED: '입장 호출을 받았습니다. 도착 제한 시각까지 매장에 도착해 주세요.',
  ARRIVED: '도착이 확인되었습니다. 매장 안내에 따라 입장해 주세요.',
  CHECKED_IN: '입장 처리가 끝났습니다. 이 웨이팅은 종료되었습니다.',
  CANCELLED: '이 웨이팅은 취소되었습니다. 다시 이용하려면 매장에서 새로 등록해 주세요.',
  NO_SHOW: '도착이 확인되지 않아 종료되었습니다. 다시 이용하려면 매장에서 새로 등록해 주세요.',
  CLOSED_BY_STORE: '매장이 웨이팅을 종료했습니다. 자세한 사유는 매장에 문의해 주세요.',
  RESERVATION_CONVERTING: '예약으로 전환하는 중입니다. 전환이 끝나면 예약 내역에서 확인할 수 있습니다.',
  RESERVATION_CONVERTED: '예약으로 전환되었습니다. 이후 일정은 예약 내역에서 확인해 주세요.',
}

/**
 * 더 진행되지 않는 상태.
 *
 * 종료된 웨이팅에서 등록 당시 순번과 앞 팀 수를 그대로 보여 주면 아직 자리가
 * 남아 있는 것처럼 읽힌다. 화면은 종료 상태에서 그 숫자를 감춘다.
 */
const CLOSED_STATUSES: readonly WaitingTeamStatus[] = [
  'CHECKED_IN',
  'CANCELLED',
  'NO_SHOW',
  'CLOSED_BY_STORE',
  'RESERVATION_CONVERTED',
]

/**
 * 소비자 취소를 서버가 허용하는 상태.
 *
 * 백엔드 `WaitingTeam.cancel`이 이 네 상태에서만 전이를 받고 나머지는
 * `WAITING_006`으로 거절한다. 눌러도 실패할 버튼을 먼저 보여 주지 않는다.
 */
const CANCELLABLE_STATUSES: readonly WaitingTeamStatus[] = [
  'WAITING',
  'CALLED',
  'ARRIVED',
  'RESERVATION_CONVERTING',
]

export function isWaitingClosed(status: WaitingTeamStatus): boolean {
  return CLOSED_STATUSES.includes(status)
}

export function isWaitingCancellable(status: WaitingTeamStatus): boolean {
  return CANCELLABLE_STATUSES.includes(status)
}

/**
 * 이 사용자가 팀 자체를 취소할 수 있는지 판정한다.
 *
 * 취소 API는 팀을 만든 계정만 받는다(`consumerAccountId` 일치). 초대로 합류한
 * 일행이 취소를 누르면 `WAITING_003`으로 떨어지므로, 일행에게는 취소 대신
 * 일행 패널의 탈퇴만 남긴다.
 */
export function canCancelWaiting(snapshot: ConsumerWaitingSnapshot): boolean {
  if (!isWaitingCancellable(snapshot.status)) {
    return false
  }
  const self = snapshot.memberships.find((membership) => membership.self)
  return self?.role === 'REPRESENTATIVE'
}

export type WaitingCancelErrorCode =
  | 'OUTCOME_UNKNOWN'
  | 'VERSION_CONFLICT'
  | 'INVALID_TRANSITION'
  | 'MEMBERSHIP_CONFLICT'
  | 'NOT_FOUND'
  | 'FORBIDDEN'
  | 'REQUEST_FAILED'

export interface WaitingCancelErrorView {
  code: WaitingCancelErrorCode
  /** 서버가 준 문구. 있으면 제목에 그대로 쓴다. */
  message?: string
}

/**
 * 취소 실패의 안내와 재시도 가능 여부.
 *
 * 상태·버전이 어긋난 실패는 같은 요청을 다시 보내도 같은 실패가 되므로
 * 재시도 대신 최신 상태 재조회로 보낸다.
 */
export const WAITING_CANCEL_GUIDANCE: Record<
  WaitingCancelErrorCode,
  WaitingPartyGuidance
> = {
  OUTCOME_UNKNOWN: {
    title: '취소 처리 여부를 확인하지 못했습니다.',
    description:
      '요청이 서버에 반영됐을 수 있습니다. 최신 상태를 다시 확인한 뒤 필요하면 한 번 더 시도해 주세요.',
    retryable: false,
  },
  VERSION_CONFLICT: {
    title: '웨이팅 상태가 방금 바뀌었습니다.',
    description: '최신 상태를 다시 확인한 뒤 취소해 주세요.',
    retryable: false,
  },
  INVALID_TRANSITION: {
    title: '지금 상태에서는 취소할 수 없습니다.',
    description: '이미 입장 처리되었거나 종료된 웨이팅입니다. 매장에 직접 문의해 주세요.',
    retryable: false,
  },
  MEMBERSHIP_CONFLICT: {
    title: '일행 구성과 취소 요청이 충돌했습니다.',
    description: '최신 상태를 다시 확인한 뒤 취소해 주세요.',
    retryable: false,
  },
  NOT_FOUND: {
    title: '취소할 웨이팅을 찾을 수 없습니다.',
    description:
      '이미 종료되었거나 대표자만 취소할 수 있습니다. 최신 상태를 다시 확인해 주세요.',
    retryable: false,
  },
  FORBIDDEN: {
    title: '현재 계정 상태로는 취소할 수 없습니다.',
    description: '계정 상태를 확인한 뒤 다시 시도해 주세요.',
    retryable: false,
  },
  REQUEST_FAILED: {
    title: '취소 요청을 처리하지 못했습니다.',
    description: '잠시 후 다시 시도해 주세요.',
    retryable: true,
  },
}

/**
 * 취소 실패를 화면 상태로 옮긴다.
 *
 * 결과 불명을 먼저 판정한다. 5xx·네트워크 끊김·멱등 키 재사용은 서버가 이미
 * 취소를 반영했을 수 있어 "다시 시도"를 권하면 안 된다.
 */
export function toWaitingCancelError(error: unknown): WaitingCancelErrorView {
  if (isOutcomeUnknown(error)) {
    return { code: 'OUTCOME_UNKNOWN' }
  }
  if (!isApiError(error)) {
    return { code: 'REQUEST_FAILED' }
  }
  const code = CANCEL_ERROR_CODES[error.code]
  if (code !== undefined) {
    return { code, message: error.message }
  }
  if (error.status === 403) {
    return { code: 'FORBIDDEN', message: error.message }
  }
  if (error.status === 404) {
    return { code: 'NOT_FOUND', message: error.message }
  }
  return { code: 'REQUEST_FAILED', message: error.message }
}

/** 계약의 `WaitingConsumerCancelConflict` 예시 code를 화면 상태로 옮긴다. */
const CANCEL_ERROR_CODES: Record<string, WaitingCancelErrorCode> = {
  WAITING_003: 'NOT_FOUND',
  WAITING_005: 'VERSION_CONFLICT',
  WAITING_006: 'INVALID_TRANSITION',
  WAITING_008: 'MEMBERSHIP_CONFLICT',
  AUTH_011: 'FORBIDDEN',
}

/** 영업일을 사람이 읽는 날짜로 옮긴다. 시각이 없는 `date` 값이다. */
export function formatBusinessDate(businessDate: string): string | null {
  const parsed = new Date(`${businessDate}T00:00:00`)
  if (Number.isNaN(parsed.getTime())) {
    return null
  }
  return parsed.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}
