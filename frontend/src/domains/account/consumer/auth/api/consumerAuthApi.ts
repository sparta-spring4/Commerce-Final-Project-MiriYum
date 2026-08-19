import { createApiClient } from '../../../../../shared/api/client'
import type { components } from '../../../../../shared/api/generated/auth-account'

/**
 * 일반 사용자 인증 endpoint 호출.
 *
 * 여기서 쓰는 client는 Access Token을 붙이지 않고 401 재발급도 걸지 않는다.
 * 재발급 요청 자체가 401을 받으면 다시 재발급을 시도해 무한 반복이 된다.
 * 보호 API용 client는 shell(ConsumerAuthProvider)이 따로 만든다.
 */
const authClient = createApiClient()

export type ConsumerSignUpRequest =
  components['schemas']['ConsumerSignUpRequest']
export type LoginRequest = components['schemas']['LoginRequest']
export type TokenData = components['schemas']['TokenData']
export type ConsumerAccount = components['schemas']['ConsumerAccount']
export type KakaoAuthorizationRequest =
  components['schemas']['KakaoAuthorizationRequest']
export type KakaoAuthenticationRequest =
  components['schemas']['KakaoAuthenticationRequest']
export type KakaoLoginData = components['schemas']['KakaoLoginData']
export type ConsumerKakaoSignUpRequest =
  components['schemas']['ConsumerKakaoSignUpRequest']

export async function signUpConsumer(
  body: ConsumerSignUpRequest,
): Promise<void> {
  await authClient('/api/v1/consumers/auth/accounts', {
    method: 'post',
    body,
  })
  // 가입 응답은 계정 식별자만 준다. 자동 로그인하지 않는다.
}

export async function signInConsumer(body: LoginRequest): Promise<TokenData> {
  const response = await authClient('/api/v1/consumers/auth/sessions', {
    method: 'post',
    body,
  })
  return response.data
}

/**
 * 카카오 인가 주소를 발급받는다.
 *
 * 응답과 함께 서버가 5분 수명의 HttpOnly state 쿠키를 심는다. 콜백 검증이
 * 그 쿠키에 걸려 있으므로 이 호출을 건너뛰고 인가 주소를 직접 만들면 안 된다.
 */
export async function createConsumerKakaoAuthorization(
  body: KakaoAuthorizationRequest,
): Promise<string> {
  const response = await authClient(
    '/api/v1/consumers/auth/kakao/authorizations',
    { method: 'post', body },
  )
  return response.data.authorizationUrl
}

/** 카카오가 돌려준 code·state를 같은 redirect URI와 함께 서버에 교환한다. */
export async function createConsumerKakaoSession(
  body: KakaoAuthenticationRequest,
): Promise<KakaoLoginData> {
  const response = await authClient('/api/v1/consumers/auth/kakao/sessions', {
    method: 'post',
    body,
  })
  return response.data
}

/** 첫 카카오 로그인에서 받은 가입 티켓으로 일반 사용자 가입을 마친다. */
export async function createConsumerKakaoAccount(
  body: ConsumerKakaoSignUpRequest,
): Promise<KakaoLoginData> {
  const response = await authClient('/api/v1/consumers/auth/kakao/accounts', {
    method: 'post',
    body,
  })
  return response.data
}

/**
 * Refresh 쿠키로 Access Token을 다시 받는다.
 *
 * 계약이 빈 JSON 본문을 요구한다. 본문을 생략하면 Content-Type이 붙지 않아
 * 서버가 415로 거절한다.
 */
export async function refreshConsumerToken(): Promise<TokenData> {
  const response = await authClient('/api/v1/consumers/auth/token-refreshes', {
    method: 'post',
    body: {},
  })
  return response.data
}

/**
 * CSRF 쿠키를 준비한다.
 *
 * 응답 본문의 token이 아니라 서버가 함께 내려주는 쿠키가 double-submit의
 * 한쪽이다. 로그아웃 직전에 호출해 쿠키가 확실히 존재하게 한다.
 */
export async function prepareConsumerCsrfToken(): Promise<string> {
  const response = await authClient(
    '/api/v1/consumers/auth/csrf-tokens/current',
    { method: 'get' },
  )
  return response.data.token
}

export async function signOutConsumer(csrfToken: string): Promise<void> {
  await authClient('/api/v1/consumers/auth/sessions/current', {
    method: 'delete',
    csrfToken,
  })
}
