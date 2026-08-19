import { describe, expect, it } from 'vitest'
import { rewriteKakaoStateCookies } from './vite.config'

describe('rewriteKakaoStateCookies', () => {
  it('removes Secure only from consumer Kakao state cookies', () => {
    expect(rewriteKakaoStateCookies([
      'MIRIYUM_CONSUMER_KAKAO_LOGIN_STATE=state; Path=/api/v1; HttpOnly; Secure; SameSite=Lax',
    ])).toEqual([
      'MIRIYUM_CONSUMER_KAKAO_LOGIN_STATE=state; Path=/api/v1; HttpOnly; SameSite=Lax',
    ])
  })

  it('rewrites store-operator Kakao link state cookies as well', () => {
    expect(rewriteKakaoStateCookies([
      'MIRIYUM_STORE_OPERATOR_KAKAO_LINK_STATE=state; Secure; SameSite=Lax',
    ])).toEqual([
      'MIRIYUM_STORE_OPERATOR_KAKAO_LINK_STATE=state; SameSite=Lax',
    ])
  })

  it('preserves refresh, CSRF, and unrelated cookies', () => {
    const cookies = [
      'MIRIYUM_CONSUMER_REFRESH=refresh; Path=/api/v1/consumers/auth; Secure; HttpOnly',
      'MIRIYUM_CONSUMER_XSRF_TOKEN=csrf; Path=/api/v1/consumers/auth; Secure',
      'MIRIYUM_PLATFORM_OPERATOR_PROOF=proof; Secure; HttpOnly',
    ]

    expect(rewriteKakaoStateCookies(cookies)).toEqual(cookies)
  })
})
