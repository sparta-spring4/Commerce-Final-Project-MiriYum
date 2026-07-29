package com.miriyum.domain.auth.jwt;

/**
 * 로그인·재발급 결과로 함께 발급되는 Access/Refresh 토큰 쌍이다.
 */
public record TokenPair(String accessToken, String refreshToken) {
}
