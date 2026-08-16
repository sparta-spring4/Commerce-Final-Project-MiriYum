/**
 * 인증·계정 오류 코드.
 *
 * backend `AuthErrorCode`·`AccountErrorCode`와 1:1로 맞춘다.
 * 화면이 문자열 리터럴을 흩뿌리지 않도록 여기서만 정의한다.
 */

export const AuthErrorCode = {
  ACCESS_TOKEN_REQUIRED: 'AUTH_001',
  ACCESS_TOKEN_EXPIRED: 'AUTH_002',
  ACCESS_TOKEN_INVALID: 'AUTH_003',
  TOKEN_NAMESPACE_MISMATCH: 'AUTH_004',
  INVALID_CREDENTIALS: 'AUTH_005',
  FORBIDDEN: 'AUTH_006',
  REFRESH_TOKEN_REQUIRED: 'AUTH_007',
  REFRESH_TOKEN_INVALID: 'AUTH_008',
  CSRF_TOKEN_INVALID: 'AUTH_009',
  ORIGIN_REJECTED: 'AUTH_010',
  ACCOUNT_RESTRICTED: 'AUTH_011',
} as const

export const AccountErrorCode = {
  EMAIL_ALREADY_EXISTS: 'ACCOUNT_001',
  PHONE_ALREADY_EXISTS: 'ACCOUNT_002',
  NICKNAME_CHANGE_TOO_SOON: 'ACCOUNT_005',
  RESERVATION_CONTACT_REQUIRED: 'ACCOUNT_006',
  CONTACT_CHANGE_NOT_ALLOWED: 'ACCOUNT_007',
} as const

/**
 * 재발급으로 복구할 수 있는 401인지 판정한다.
 *
 * 만료(AUTH_002)만 재발급 대상이다. 형식·서명 오류(AUTH_003)와 namespace
 * 불일치(AUTH_004)는 재발급해도 같은 결과이고, namespace 불일치는 다른 shell의
 * 토큰을 쓴 상황이라 조용히 이어 붙이면 shell 분리 규칙이 깨진다.
 */
export function isRefreshableAuthError(code: string): boolean {
  return code === AuthErrorCode.ACCESS_TOKEN_EXPIRED
}
