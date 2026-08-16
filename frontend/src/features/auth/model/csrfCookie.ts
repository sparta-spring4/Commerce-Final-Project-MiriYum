/**
 * 계정 shell별 CSRF 쿠키.
 *
 * CSRF 쿠키는 HttpOnly가 아니다. double-submit이 성립하려면 클라이언트가 값을
 * 읽어 헤더로 되돌려 보내야 하기 때문이다. Refresh 쿠키는 반대로 HttpOnly라
 * 여기서 읽을 수 없고 읽으려 시도하지도 않는다.
 */

export const CONSUMER_CSRF_COOKIE = 'MIRIYUM_CONSUMER_XSRF_TOKEN'
export const STORE_OPERATOR_CSRF_COOKIE = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN'

/**
 * 이름이 정확히 일치하는 쿠키 값을 읽는다.
 *
 * `document.cookie.includes(name)`으로 찾으면 다른 shell의 쿠키 이름이
 * 부분 일치해 교차 namespace 값을 집어 올 수 있다. 정확히 분해해서 비교한다.
 */
export function readCookie(
  name: string,
  cookieSource: string = document.cookie,
): string | null {
  for (const entry of cookieSource.split(';')) {
    const separator = entry.indexOf('=')
    if (separator < 0) {
      continue
    }
    if (entry.slice(0, separator).trim() !== name) {
      continue
    }
    return decodeURIComponent(entry.slice(separator + 1).trim())
  }
  return null
}
