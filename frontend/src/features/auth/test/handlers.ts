import { http } from 'msw'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { AuthErrorCode } from '../model/authErrors'

/**
 * 인증 shell 테스트용 MSW 핸들러.
 *
 * 앱을 렌더하면 shell이 항상 세션 복구를 시도하므로 재발급 핸들러는
 * 모든 화면 테스트에 필요하다.
 */

export const CONSUMER_REFRESH_PATH = '/api/v1/consumers/auth/token-refreshes'
export const CONSUMER_SESSIONS_PATH = '/api/v1/consumers/auth/sessions'
export const CONSUMER_SESSION_CURRENT_PATH =
  '/api/v1/consumers/auth/sessions/current'
export const CONSUMER_CSRF_PATH = '/api/v1/consumers/auth/csrf-tokens/current'
export const CONSUMER_ACCOUNTS_PATH = '/api/v1/consumers/auth/accounts'

export function tokenData(accessToken = 'consumer-access-token') {
  return { accessToken, tokenType: 'Bearer', expiresIn: 3600 }
}

/** 비로그인 상태. 세션 복구가 실패하는 것이 정상 경로다. */
export const unauthenticatedConsumer = http.post(CONSUMER_REFRESH_PATH, () =>
  errorResponse(
    401,
    AuthErrorCode.REFRESH_TOKEN_REQUIRED,
    'Refresh Token 쿠키가 필요합니다.',
  ),
)

/** 새로고침 후 세션이 복구되는 상태. */
export function authenticatedConsumer(accessToken?: string) {
  return http.post(CONSUMER_REFRESH_PATH, () =>
    successResponse(tokenData(accessToken)),
  )
}

/** 로그아웃 경로 두 개를 한 번에 등록한다. */
export function signOutHandlers() {
  return [
    http.get(CONSUMER_CSRF_PATH, () =>
      successResponse({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' }),
    ),
    http.delete(CONSUMER_SESSION_CURRENT_PATH, () => successResponse(null)),
  ]
}
