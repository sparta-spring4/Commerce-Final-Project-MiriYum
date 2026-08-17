import { http } from 'msw'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { AuthErrorCode } from '../../auth/model/authErrors'

/**
 * 플랫폼 운영자 shell 테스트용 MSW 핸들러.
 *
 * 콘솔을 렌더하면 shell이 항상 세션 복구를 시도하므로 재발급 핸들러는
 * 모든 콘솔 화면 테스트에 필요하다.
 */

export const PO_REFRESH_PATH =
  '/api/v1/platform-operators/auth/token-refreshes'
export const PO_SESSIONS_PATH = '/api/v1/platform-operators/auth/sessions'
export const PO_SESSION_CURRENT_PATH =
  '/api/v1/platform-operators/auth/sessions/current'
export const PO_CSRF_PATH =
  '/api/v1/platform-operators/auth/csrf-tokens/current'
export const PO_INITIAL_PASSWORD_PATH =
  '/api/v1/platform-operators/auth/initial-password'
export const PO_MEMBERS_PATH = '/api/v1/platform-operators/members'
export const PO_AUDIT_EVENTS_PATH = '/api/v1/platform-operators/audit-events'

/**
 * 토큰 응답.
 *
 * 계약에 없는 필드를 넣지 않는다. 특히 역할·권한은 이 응답에 없다.
 * 테스트가 그것을 넣어 두면, 화면이 토큰에서 권한을 읽는 구현으로 흘러도
 * 테스트가 통과해 버린다.
 */
export function platformOperatorTokenData(
  overrides: { passwordChangeRequired?: boolean; accessToken?: string } = {},
) {
  return {
    accessToken: overrides.accessToken ?? 'platform-operator-access-token',
    tokenType: 'Bearer',
    expiresIn: 900,
    passwordChangeRequired: overrides.passwordChangeRequired ?? false,
    idleExpiresAt: '2026-08-17T10:15:00Z',
    absoluteExpiresAt: '2026-08-17T18:00:00Z',
  }
}

/** 비로그인 상태. 세션 복구가 실패하는 것이 정상 경로다. */
export const unauthenticatedPlatformOperator = http.post(
  PO_REFRESH_PATH,
  () =>
    errorResponse(
      401,
      AuthErrorCode.REFRESH_TOKEN_REQUIRED,
      'Refresh Token 쿠키가 필요합니다.',
    ),
)

/** 새로고침 후 정상 세션이 복구되는 상태. */
export function authenticatedPlatformOperator(accessToken?: string) {
  return http.post(PO_REFRESH_PATH, () =>
    successResponse(platformOperatorTokenData({ accessToken })),
  )
}

/** 임시 비밀번호 상태로 복구되는 세션. 업무 endpoint는 아직 쓸 수 없다. */
export const restrictedPlatformOperator = http.post(PO_REFRESH_PATH, () =>
  successResponse(platformOperatorTokenData({ passwordChangeRequired: true })),
)
