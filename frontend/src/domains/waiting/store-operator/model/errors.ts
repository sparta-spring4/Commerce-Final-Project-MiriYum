import { isApiError, isNetworkError } from '../../../../shared/api/apiError'
import { CommonErrorCode } from '../../../../shared/api/envelope'
import { AuthErrorCode } from '../../../../shared/auth/authErrors'
import { StoreErrorCode } from '../../../store/store-operator/model/storeErrors'

/**
 * 웨이팅 도메인 오류 코드.
 *
 * 활성 Waiting OpenAPI가 선언한 코드와 1:1로 맞춘다. 매장 접근·상태 코드는
 * 웨이팅 계약에서도 store 코드를 그대로 쓰므로 그쪽 정의를 재사용한다.
 */
export const WaitingErrorCode = {
  SETTING_VERSION_CONFLICT: 'WAITING_001',
  DISABLE_ACTION_REQUIRED: 'WAITING_002',
  TEAM_NOT_FOUND: 'WAITING_003',
  CLOSURE_JOB_NOT_FOUND: 'WAITING_004',
  TEAM_VERSION_CONFLICT: 'WAITING_005',
  TRANSITION_NOT_ALLOWED: 'WAITING_006',
  NOT_QUEUE_HEAD: 'WAITING_007',
  ACTIVE_MEMBERSHIP_CONFLICT: 'WAITING_008',
  CLOSURE_RECONCILIATION_REQUIRED: 'WAITING_009',
  CLOSURE_PARTIAL_FAILURE: 'WAITING_010',
} as const

/**
 * 조회 후 서버 상태가 바뀌어 재조회가 필요한 충돌인지 판정한다.
 *
 * 이 판정이 화면의 복구 동작을 정한다. 재조회로 풀리는 충돌은 입력을 지우지 않고
 * 최신 값만 다시 읽어 오면 되지만, 상태 자체가 불가능한 전이는 다시 읽어도 같다.
 */
export function isStaleVersionConflict(error: unknown): boolean {
  if (!isApiError(error)) {
    return false
  }
  return (
    error.code === WaitingErrorCode.SETTING_VERSION_CONFLICT ||
    error.code === WaitingErrorCode.TEAM_VERSION_CONFLICT
  )
}

/** 활성 팀 처리 방법을 고르지 않아 거절된 요청인지 판정한다. */
export function needsDisableAction(error: unknown): boolean {
  return (
    isApiError(error) && error.code === WaitingErrorCode.DISABLE_ACTION_REQUIRED
  )
}

/**
 * 서버 오류를 운영자용 문구로 옮긴다.
 *
 * 문구는 서버가 준 code로만 정한다. 화면이 상태를 추측해 사유를 지어내면
 * 실제 거절 이유와 어긋난 안내를 하게 된다.
 */
export function waitingErrorMessage(error: unknown): string {
  if (isNetworkError(error)) {
    return '서버에 연결하지 못했습니다. 처리 여부가 확정되지 않았으니 상태를 다시 확인해 주세요.'
  }
  if (!isApiError(error)) {
    return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  switch (error.code) {
    case WaitingErrorCode.SETTING_VERSION_CONFLICT:
      return '조회 후 웨이팅 설정이 바뀌었습니다. 최신 설정을 다시 확인해 주세요.'
    case WaitingErrorCode.DISABLE_ACTION_REQUIRED:
      return '대기 중인 팀이 있습니다. 유지할지 일괄 종결할지 선택해 주세요.'
    case WaitingErrorCode.TEAM_NOT_FOUND:
      return '웨이팅 팀을 찾을 수 없습니다. 목록을 새로 조회해 주세요.'
    case WaitingErrorCode.CLOSURE_JOB_NOT_FOUND:
      return '종결 작업을 찾을 수 없습니다.'
    case WaitingErrorCode.TEAM_VERSION_CONFLICT:
      return '조회 후 이 팀의 상태가 바뀌었습니다. 최신 상태를 다시 확인해 주세요.'
    case WaitingErrorCode.TRANSITION_NOT_ALLOWED:
      return '현재 상태에서는 이 처리를 할 수 없습니다.'
    case WaitingErrorCode.NOT_QUEUE_HEAD:
      return '대기 순서상 맨 앞 팀만 호출할 수 있습니다.'
    case WaitingErrorCode.ACTIVE_MEMBERSHIP_CONFLICT:
      return '고객의 다른 활성 웨이팅과 충돌합니다.'
    case WaitingErrorCode.CLOSURE_RECONCILIATION_REQUIRED:
      return '종결 결과 확인이 필요합니다. 작업 상태를 다시 조회해 주세요.'
    case WaitingErrorCode.CLOSURE_PARTIAL_FAILURE:
      return '일부 팀을 종결하지 못했습니다. 작업 상태에서 남은 건수를 확인해 주세요.'
    case StoreErrorCode.NOT_FOUND:
      return '매장을 찾을 수 없습니다.'
    case StoreErrorCode.ACCESS_DENIED:
      return '이 매장의 대표 운영자가 아닙니다.'
    case StoreErrorCode.STATE_CONFLICT:
      return '현재 매장 상태에서는 이 작업을 할 수 없습니다.'
    case StoreErrorCode.VERIFICATION_STATE_CONFLICT:
      return '입점 검증이 승인된 매장만 웨이팅을 운영할 수 있습니다.'
    case AuthErrorCode.ACCOUNT_RESTRICTED:
      return '현재 계정 상태로는 이용할 수 없습니다. 고객센터에 문의해 주세요.'
    case CommonErrorCode.VALIDATION_FAILED:
      return '입력한 내용을 다시 확인해 주세요.'
    case CommonErrorCode.TOO_MANY_REQUESTS:
      return '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    case CommonErrorCode.SERVICE_UNAVAILABLE:
      return '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
    default:
      return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}
