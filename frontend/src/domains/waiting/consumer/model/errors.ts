import { isApiError } from '../../../../shared/api/apiError'
import type {
  WaitingJoinErrorView,
  WaitingPartyActionKind,
  WaitingPartyErrorCode,
  WaitingPartyErrorView,
} from './partyViewState'

/** API 오류 code를 초대 합류 화면의 복구 경로로 옮긴다. */
export function toWaitingJoinError(error: unknown): WaitingJoinErrorView {
  if (!isApiError(error)) return { code: 'REQUEST_FAILED' }

  switch (error.code) {
    case 'WAITING_011':
      return { code: 'ACCOUNT_ACTIVE_WAITING_EXISTS' }
    case 'WAITING_014':
      // 서버는 잘못됨·만료·철회·사용 완료를 구분하지 않는다.
      return { code: 'INVALID_CODE' }
    case 'WAITING_015':
      return { code: 'TEAM_STATE_CHANGED' }
    case 'WAITING_017':
      return { code: 'PARTY_CAPACITY_EXCEEDED' }
    default:
      return { code: 'REQUEST_FAILED' }
  }
}

const PARTY_ERROR_CODES: Record<string, WaitingPartyErrorCode> = {
  WAITING_003: 'NOT_FOUND',
  WAITING_005: 'VERSION_CONFLICT',
  WAITING_014: 'INVITATION_INVALID',
  WAITING_015: 'MUTATION_NOT_ALLOWED',
  WAITING_016: 'TRANSFER_INVALID',
  WAITING_017: 'CAPACITY_EXCEEDED',
}

/** API 오류 code를 실패한 일행 action 가까이 표시할 view state로 옮긴다. */
export function toWaitingPartyError(
  action: WaitingPartyActionKind,
  error: unknown,
): WaitingPartyErrorView {
  const code = isApiError(error)
    ? (PARTY_ERROR_CODES[error.code] ?? 'REQUEST_FAILED')
    : 'REQUEST_FAILED'
  return { action, code }
}
