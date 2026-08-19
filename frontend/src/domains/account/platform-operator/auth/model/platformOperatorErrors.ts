import { AuthErrorCode } from '../../../../../shared/auth/authErrors'

/**
 * 플랫폼 운영자 shell이 별도로 해석해야 하는 오류 코드.
 *
 * backend `AuthErrorCode`는 세 audience가 함께 쓰는 하나의 카탈로그다. 여기서
 * 다시 정의하지 않고 필요한 것만 이름을 붙여 가져온다.
 */
export const PlatformOperatorAuthErrorCode = {
  /** 최초 비밀번호를 바꾸기 전에는 다른 업무 endpoint를 쓸 수 없다. */
  INITIAL_PASSWORD_CHANGE_REQUIRED: AuthErrorCode.INITIAL_PASSWORD_CHANGE_REQUIRED,
  /** 중앙 세션이 회수됐다. Access Token이 아직 안 만료됐어도 무효다. */
  SESSION_INVALID: AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID,
  ACCOUNT_RESTRICTED: AuthErrorCode.ACCOUNT_RESTRICTED,
  INVALID_CREDENTIALS: AuthErrorCode.INVALID_CREDENTIALS,
} as const

/**
 * 재발급으로 복구할 수 있는 401인지 판정한다.
 *
 * 일반 사용자 shell과 갈라지는 지점이 하나 있다. 플랫폼 운영자는 stateless
 * JWT가 아니라 서버가 들고 있는 중앙 세션을 함께 본다. 세션이 회수되면
 * `AUTH_015`가 오는데, 이건 Access Token 만료가 아니라 세션 자체가 사라진
 * 것이라 재발급해도 같은 결과다. 재발급 대상에 넣으면 로그아웃된 운영자가
 * 회전을 계속 시도하게 된다.
 */
export function isRefreshablePlatformOperatorAuthError(code: string): boolean {
  return code === AuthErrorCode.ACCESS_TOKEN_EXPIRED
}

/**
 * 최초 비밀번호 변경이 필요한 상태인지 판정한다.
 *
 * 두 경로로 알 수 있다. 로그인·회전 응답의 `passwordChangeRequired`가 하나고,
 * 제한 세션으로 업무 endpoint를 부를 때 오는 `AUTH_012`가 다른 하나다.
 * 응답만 믿으면 다른 탭에서 열어 둔 화면이 제한 세션인 걸 모른 채 남는다.
 */
export function isInitialPasswordChangeRequired(code: string): boolean {
  return code === AuthErrorCode.INITIAL_PASSWORD_CHANGE_REQUIRED
}
