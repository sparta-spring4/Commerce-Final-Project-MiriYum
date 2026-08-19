/**
 * 플랫폼 운영자 shell의 CSRF 쿠키.
 *
 * backend `TokenNamespace.PLATFORM_OPERATOR`가 정한 이름이며 쿠키 Path는
 * `/api/v1/platform-operators/auth`다. 다른 shell의 쿠키와 이름·Path가 모두
 * 달라야 한 브라우저에서 두 세션이 섞이지 않는다.
 *
 * 읽기는 `shared/auth/readCookie`의 사용자 중립 쿠키 파싱 규칙을 쓴다.
 * 이름을 정확히 분해해 비교하므로 부분 일치로 다른 shell 값을 집지 않는다.
 */
export const PLATFORM_OPERATOR_CSRF_COOKIE =
  'MIRIYUM_PLATFORM_OPERATOR_XSRF_TOKEN'
