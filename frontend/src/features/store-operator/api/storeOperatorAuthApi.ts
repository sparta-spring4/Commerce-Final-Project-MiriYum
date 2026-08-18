import { createApiClient } from '../../../shared/api/client'
import type { components } from '../../../shared/api/generated/auth-account'

/**
 * 매장 운영자 인증 endpoint 호출.
 *
 * 일반 사용자와 같은 모양이지만 namespace가 다르므로 함수를 공유하지 않는다.
 * 한 함수에 namespace를 인자로 넘기면 호출 지점의 실수 하나로 다른 shell의
 * 세션을 건드릴 수 있다.
 *
 * 여기 client는 Access Token을 붙이지 않고 401 재발급도 걸지 않는다.
 * 재발급 요청 자체가 401을 받으면 다시 재발급을 시도해 무한 반복이 된다.
 */
const authClient = createApiClient()

export type StoreOperatorSignUpRequest =
  components['schemas']['StoreOperatorSignUpRequest']
export type LoginRequest = components['schemas']['LoginRequest']
export type TokenData = components['schemas']['TokenData']
export type StoreOperatorAccount =
  components['schemas']['StoreOperatorAccount']

export async function signUpStoreOperator(
  body: StoreOperatorSignUpRequest,
): Promise<void> {
  await authClient('/api/v1/store-operators/auth/accounts', {
    method: 'post',
    body,
  })
  // 계정 생성과 매장 등록을 한 요청으로 합치지 않는다. 성공 후 로그인한다.
}

export async function signInStoreOperator(
  body: LoginRequest,
): Promise<TokenData> {
  const response = await authClient('/api/v1/store-operators/auth/sessions', {
    method: 'post',
    body,
  })
  return response.data
}

/**
 * Refresh 쿠키로 Access Token을 다시 받는다.
 * 계약이 빈 JSON 본문을 요구한다. 본문을 생략하면 Content-Type이 붙지 않아 415가 된다.
 */
export async function refreshStoreOperatorToken(): Promise<TokenData> {
  const response = await authClient(
    '/api/v1/store-operators/auth/token-refreshes',
    { method: 'post', body: {} },
  )
  return response.data
}

/**
 * CSRF 쿠키를 준비한다.
 * 응답 본문의 token이 아니라 서버가 함께 내려주는 쿠키가 double-submit의 한쪽이다.
 */
export async function prepareStoreOperatorCsrfToken(): Promise<string> {
  const response = await authClient(
    '/api/v1/store-operators/auth/csrf-tokens/current',
    { method: 'get' },
  )
  return response.data.token
}

export async function signOutStoreOperator(csrfToken: string): Promise<void> {
  await authClient('/api/v1/store-operators/auth/sessions/current', {
    method: 'delete',
    csrfToken,
  })
}
