import { createApiClient } from '../../../shared/api/client'
import type { components } from '../../../shared/api/generated/platform-operator-auth'

/**
 * 플랫폼 운영자 인증 endpoint 호출.
 *
 * 일반 사용자·매장 운영자와 같은 이유로 여기서 쓰는 client는 Access Token을
 * 붙이지 않고 401 재발급도 걸지 않는다. 재발급 요청이 401을 받으면 다시
 * 재발급을 시도해 무한 반복이 된다. 보호 API용 client는 shell이 따로 만든다.
 *
 * 이 모듈은 `/api/v1/platform-operators/auth` 아래만 호출한다. 일반 사용자·
 * 대표자 인증 경로를 섞어 쓰면 namespace 분리가 깨진다.
 */
const authClient = createApiClient()

export type PlatformOperatorLoginRequest =
  components['schemas']['PlatformOperatorLoginRequest']
export type PlatformOperatorTokenData =
  components['schemas']['PlatformOperatorTokenData']
export type InitialPasswordChangeRequest =
  components['schemas']['InitialPasswordChangeRequest']

export async function signInPlatformOperator(
  body: PlatformOperatorLoginRequest,
): Promise<PlatformOperatorTokenData> {
  const response = await authClient(
    '/api/v1/platform-operators/auth/sessions',
    { method: 'post', body },
  )
  return response.data
}

/**
 * Refresh 쿠키로 Access Token을 회전한다.
 *
 * 계약이 빈 JSON 본문을 요구한다. 본문을 생략하면 Content-Type이 붙지 않아
 * 서버가 415로 거절한다. 서버는 현재 Refresh Token을 한 번 소비하므로
 * 이 호출을 동시에 두 번 띄우면 한쪽이 반드시 실패한다.
 */
export async function refreshPlatformOperatorToken(): Promise<PlatformOperatorTokenData> {
  const response = await authClient(
    '/api/v1/platform-operators/auth/token-refreshes',
    { method: 'post', body: {} },
  )
  return response.data
}

/**
 * CSRF 쿠키를 준비한다.
 *
 * 응답 본문의 token이 아니라 서버가 함께 내려주는 쿠키가 double-submit의
 * 한쪽이다. 로그아웃 직전에 호출해 쿠키가 확실히 존재하게 한다.
 */
export async function preparePlatformOperatorCsrfToken(): Promise<string> {
  const response = await authClient(
    '/api/v1/platform-operators/auth/csrf-tokens/current',
    { method: 'get' },
  )
  return response.data.token
}

export async function signOutPlatformOperator(
  csrfToken: string,
): Promise<void> {
  await authClient('/api/v1/platform-operators/auth/sessions/current', {
    method: 'delete',
    csrfToken,
  })
}

/**
 * 최초 임시 비밀번호를 새 비밀번호로 바꾼다.
 *
 * 제한 세션에서만 부를 수 있고, 성공하면 서버가 새 토큰을 내려준다.
 * 응답 토큰을 그대로 shell에 넣어야 사용자가 다시 로그인하지 않는다.
 */
export async function replacePlatformOperatorInitialPassword(
  accessToken: string,
  body: InitialPasswordChangeRequest,
): Promise<PlatformOperatorTokenData> {
  // 제한 세션의 Access Token이 필요하다. 이 호출만 토큰을 붙인다.
  const restrictedSessionClient = createApiClient({
    getAccessToken: () => accessToken,
  })
  const response = await restrictedSessionClient(
    '/api/v1/platform-operators/auth/initial-password',
    { method: 'put', body },
  )
  return response.data
}
