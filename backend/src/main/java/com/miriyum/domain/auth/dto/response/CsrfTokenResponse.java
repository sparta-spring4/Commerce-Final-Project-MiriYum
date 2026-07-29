package com.miriyum.domain.auth.dto.response;

/**
 * CSRF 토큰 준비 응답이다. token은 같은 namespace CSRF 쿠키에 설정된 값과 동일하다.
 */
public record CsrfTokenResponse(
        String token,
        String headerName
) {

    public static CsrfTokenResponse of(String token) {
        return new CsrfTokenResponse(token, "X-CSRF-TOKEN");
    }
}
