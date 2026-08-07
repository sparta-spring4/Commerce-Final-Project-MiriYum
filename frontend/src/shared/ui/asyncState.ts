import { ApiError, isApiError, isNetworkError } from '../api/apiError'
import { CommonErrorCode } from '../api/envelope'

/**
 * docs/08-ui-and-frontend-guidelines.md가 요구하는 비동기 화면 상태다.
 * 이름은 시안 문구가 아니라 그 문서의 목록에서 가져온다.
 */
export type AsyncState =
  | 'initial'
  | 'loading'
  | 'empty'
  | 'success'
  | 'validationError'
  | 'forbidden'
  | 'conflict'
  | 'indeterminate'
  | 'retryable'
  | 'awaitingRecovery'

/** 사용자가 지금 할 수 있는 다음 행동. 상태별 UI 분기의 기준이다. */
export type AsyncAction = 'none' | 'retry' | 'fixInput' | 'signIn' | 'recheck'

export interface AsyncStatePresentation {
  state: AsyncState
  action: AsyncAction
}

const UNAUTHENTICATED_STATUS = 401
const FORBIDDEN_STATUS = 403
const CONFLICT_STATUS = 409
const SERVER_ERROR_STATUS = 500

/**
 * 오류를 화면 상태로 옮긴다. HTTP status와 공통 응답 code를 함께 평가한다.
 * 서버 오류 코드를 자체 해석해 성공으로 바꾸지 않는다.
 */
export function toAsyncState(error: unknown): AsyncStatePresentation {
  // 서버에 닿지 못한 실패는 확정 결과가 아니다.
  if (isNetworkError(error)) {
    return { state: 'indeterminate', action: 'recheck' }
  }
  if (!isApiError(error)) {
    return { state: 'retryable', action: 'retry' }
  }

  const apiError: ApiError = error

  if (apiError.status === UNAUTHENTICATED_STATUS) {
    return { state: 'forbidden', action: 'signIn' }
  }
  if (apiError.status === FORBIDDEN_STATUS) {
    return { state: 'forbidden', action: 'none' }
  }
  if (apiError.status === CONFLICT_STATUS) {
    return { state: 'conflict', action: 'recheck' }
  }
  if (apiError.code === CommonErrorCode.VALIDATION_FAILED) {
    return { state: 'validationError', action: 'fixInput' }
  }
  if (apiError.code === CommonErrorCode.TOO_MANY_REQUESTS) {
    return { state: 'retryable', action: 'retry' }
  }
  if (apiError.code === CommonErrorCode.SERVICE_UNAVAILABLE) {
    return { state: 'awaitingRecovery', action: 'recheck' }
  }
  if (apiError.status >= SERVER_ERROR_STATUS) {
    return { state: 'retryable', action: 'retry' }
  }
  return { state: 'validationError', action: 'fixInput' }
}

/** 성공 결과가 비었는지 판정한다. 빈 배열은 오류가 아니라 정상 empty result다. */
export function isEmptyResult(data: unknown): boolean {
  if (data === null || data === undefined) {
    return true
  }
  return Array.isArray(data) && data.length === 0
}
