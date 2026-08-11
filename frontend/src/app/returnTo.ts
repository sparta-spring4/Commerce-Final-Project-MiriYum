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
 * 보존한 목적지를 읽는다. 같은 오리진 안의 절대 경로만 허용한다.
 *
 * 문자열 접두사 검사만으로는 부족하다. 브라우저 URL 파서는 `/\evil.example`을
 * `http://evil.example/`로 정규화하므로 `/`로 시작한다는 이유만으로 통과시키면
 * 로그인 복귀에서 외부 오리진으로 나갈 수 있다. 그래서 기준 오리진으로 실제
 * 파싱한 뒤 결과 오리진이 정확히 같은지 확인한다.
 */
export function readReturnTo(
  search: string,
  baseOrigin: string = window.location.origin,
): string | null {
  const value = new URLSearchParams(search).get(RETURN_TO_PARAM)
  if (!value) {
    return null
  }
  // 역슬래시는 파서가 슬래시로 취급해 //host 형태로 정규화될 수 있다.
  if (value.includes('\\')) {
    return null
  }
  if (!value.startsWith('/')) {
    return null
  }

  let resolved: URL
  try {
    resolved = new URL(value, baseOrigin)
  } catch {
    return null
  }
  if (resolved.origin !== new URL(baseOrigin).origin) {
    return null
  }

  // query와 hash는 목적지의 일부이므로 보존한다.
  return `${resolved.pathname}${resolved.search}${resolved.hash}`
}
