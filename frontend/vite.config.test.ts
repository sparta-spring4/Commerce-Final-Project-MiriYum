import { describe, expect, it } from 'vitest'
import { rewriteKakaoStateCookies } from './vite.config'

describe('rewriteKakaoStateCookies', () => {
  it.each([
    'MIRIYUM_CONSUMER_KAKAO_LOGIN_STATE',
    'MIRIYUM_CONSUMER_KAKAO_LINK_STATE',
    'MIRIYUM_STORE_OPERATOR_KAKAO_LOGIN_STATE',
    'MIRIYUM_STORE_OPERATOR_KAKAO_LINK_STATE',
  ])('removes Secure from the allowed %s cookie', (cookieName) => {
    expect(rewriteKakaoStateCookies([
      `${cookieName}=state; Path=/api/v1; HttpOnly; Secure; SameSite=Lax`,
    ])).toEqual([
      `${cookieName}=state; Path=/api/v1; HttpOnly; SameSite=Lax`,
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
