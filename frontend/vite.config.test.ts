import { describe, expect, it } from 'vitest'
import {
  portOneBrowserDefines,
  rewriteKakaoStateCookies,
} from './vite.config'

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

describe('PortOne browser environment', () => {
  it('Store ID와 Channel Key만 브라우저 코드에 공개한다', () => {
    const defines = portOneBrowserDefines({
      MIRIYUM_PORTONE_STORE_ID: 'store-visible',
      MIRIYUM_PORTONE_CHANNEL_KEY: 'channel-visible',
      MIRIYUM_PORTONE_API_SECRET: 'must-not-be-exposed',
      MIRIYUM_PORTONE_WEBHOOK_SECRET: 'must-not-be-exposed-either',
    })

    expect(defines).toEqual({
      'import.meta.env.MIRIYUM_PORTONE_STORE_ID': JSON.stringify('store-visible'),
      'import.meta.env.MIRIYUM_PORTONE_CHANNEL_KEY':
        JSON.stringify('channel-visible'),
    })
    expect(JSON.stringify(defines)).not.toContain('must-not-be-exposed')
  })
})
