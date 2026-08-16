import { describe, expect, it } from 'vitest'
import { KAKAO_CALLBACK_PATH, kakaoRedirectUri } from './kakaoOAuth'

describe('카카오 콜백 주소', () => {
  it('계약이 요구하는 절대 주소로 만든다', () => {
    // 계약의 redirectUri는 format: uri다. 상대 경로는 서버 허용 목록과 대조되지 않는다.
    expect(kakaoRedirectUri()).toBe(
      `${window.location.origin}${KAKAO_CALLBACK_PATH}`,
    )
  })

  it('현재 화면의 query와 hash를 섞지 않는다', () => {
    // 목적지 보존용 ?returnTo=가 붙은 로그인 화면에서 눌러도 주소는 같아야 한다.
    // 한 글자라도 다르면 서버 허용 목록 대조가 실패해 503이 된다.
    window.history.pushState({}, '', '/sign-in?returnTo=%2Fmypage#form')

    expect(kakaoRedirectUri()).toBe(
      `${window.location.origin}${KAKAO_CALLBACK_PATH}`,
    )
  })
})
