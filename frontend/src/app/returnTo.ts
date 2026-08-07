/**
 * 인증이 필요한 지점에서 원래 가려던 경로를 보존한다.
 *
 * 이 모듈은 보존과 조회 수단만 제공한다. 복귀 여부와 교차 namespace 차단은
 * 인증 shell(#192)이 판단한다.
 */

const RETURN_TO_PARAM = 'returnTo'

/** 로그인 경로에 원래 목적지를 붙인다. */
export function withReturnTo(signInPath: string, destination: string): string {
  return `${signInPath}?${RETURN_TO_PARAM}=${encodeURIComponent(destination)}`
}

/**
 * 보존한 목적지를 읽는다.
 *
 * 외부 오리진으로 나가는 값은 열린 리다이렉트가 되므로 받지 않는다.
 * 같은 오리진 안의 절대 경로만 허용한다.
 */
export function readReturnTo(search: string): string | null {
  const value = new URLSearchParams(search).get(RETURN_TO_PARAM)
  if (!value) {
    return null
  }
  // '//host' 형태는 프로토콜 상대 URL이라 외부로 나간다.
  if (!value.startsWith('/') || value.startsWith('//')) {
    return null
  }
  return value
}
