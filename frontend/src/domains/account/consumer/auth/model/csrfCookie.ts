/**
 * 계정 shell별 CSRF 쿠키.
 *
 * CSRF 쿠키는 HttpOnly가 아니다. double-submit이 성립하려면 클라이언트가 값을
 * 읽어 헤더로 되돌려 보내야 하기 때문이다. Refresh 쿠키는 반대로 HttpOnly라
 * 여기서 읽을 수 없고 읽으려 시도하지도 않는다.
 */

export const CONSUMER_CSRF_COOKIE = 'MIRIYUM_CONSUMER_XSRF_TOKEN'
