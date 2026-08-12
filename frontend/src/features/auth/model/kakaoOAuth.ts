/**
 * 카카오 로그인 시작에 필요한 값과 이음새.
 *
 * 인가 주소(`https://kauth.kakao.com/oauth/authorize?...`)는 **서버가 만든다.**
 * client_id와 state는 프론트가 조립하지 않는다. state는 서버가 서명해 발급하고
 * 같은 브라우저의 HttpOnly 쿠키와 대조되므로, 프론트가 흉내 내면 검증이 깨진다.
 */

/**
 * 카카오가 인가 코드를 돌려줄 화면의 경로.
 *
 * 이 값은 카카오 개발자 콘솔과 서버 `MIRIYUM_KAKAO_REDIRECT_URIS` 허용 목록에
 * **글자 그대로 같게** 등록돼야 한다. 어느 한쪽이라도 다르면 서버가 503으로 막는다.
 *
 * 콜백 화면 자체는 후속 작업이라 `app/routes.ts`에 아직 등록하지 않는다.
 * 여기 있는 동안에는 route가 아니라 서버로 보내는 값일 뿐이다.
 */
export const KAKAO_CALLBACK_PATH = '/auth/kakao/callback'

/**
 * 서버 허용 목록과 대조할 절대 주소를 만든다.
 *
 * 현재 화면의 query·hash는 섞지 않는다. `?returnTo=...`가 붙으면 허용 목록의
 * 문자열과 달라져 같은 배포에서도 요청이 거절된다.
 */
export function kakaoRedirectUri(): string {
  return new URL(KAKAO_CALLBACK_PATH, window.location.origin).toString()
}

/**
 * 브라우저 전체 이동. 한 겹 감싸는 이유는 두 가지다.
 *
 * 카카오 인가 화면은 SPA route가 아니라 다른 오리진이므로 react-router로 갈 수 없고,
 * jsdom에는 `location.assign` 구현이 없어 테스트가 이 지점을 대체해야 한다.
 */
export const browserRedirect = {
  assign(url: string): void {
    window.location.assign(url)
  },
}
