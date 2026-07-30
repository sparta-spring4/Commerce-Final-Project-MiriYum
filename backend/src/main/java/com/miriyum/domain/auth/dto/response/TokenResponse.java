package com.miriyum.domain.auth.dto.response;

/**
 * 로그인·재발급 성공 응답이다. Refresh JWT는 응답 본문에 담지 않고 namespace별 쿠키로만 전달한다.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {

    public static TokenResponse of(String accessToken, long expiresInSeconds) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
