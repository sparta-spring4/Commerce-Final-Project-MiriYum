package com.miriyum.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final String SECRET = "test-only-secret-key-must-be-at-least-32-bytes";
    private static final String ISSUER = "miriyum-test";

    @Test
    @DisplayName("발급한 Access Token을 검증하면 namespace와 계정 ID를 복원한다")
    void parsesIssuedAccessToken() {
        // given
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));

        // when
        String accessToken = provider.generateAccessToken(TokenNamespace.CONSUMER, 42L);
        ParsedToken parsed = provider.parseAccessToken(accessToken);

        // then
        assertThat(parsed.namespace()).isEqualTo(TokenNamespace.CONSUMER);
        assertThat(parsed.accountId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("만료 시각이 지난 Access Token을 검증하면 만료 오류를 던진다")
    void rejectsExpiredAccessToken() {
        // given
        JwtTokenProvider issuingProvider =
                new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));
        String accessToken = issuingProvider.generateAccessToken(TokenNamespace.CONSUMER, 1L);
        JwtTokenProvider verifyingProvider =
                new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T02:00:00Z"));

        // when & then
        assertThatThrownBy(() -> verifyingProvider.parseAccessToken(accessToken))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("Refresh Token을 Access Token 자리에 사용하면 거부한다")
    void rejectsRefreshTokenUsedAsAccessToken() {
        // given
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));
        String refreshToken = provider.generateRefreshToken(TokenNamespace.CONSUMER, 7L);

        // when & then
        assertThatThrownBy(() -> provider.parseAccessToken(refreshToken))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    @DisplayName("변조된 서명의 토큰을 검증하면 유효하지 않은 토큰으로 거부한다")
    void rejectsTamperedToken() {
        // given
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));
        String accessToken = provider.generateAccessToken(TokenNamespace.CONSUMER, 1L);
        String tampered = accessToken.substring(0, accessToken.length() - 1)
                + (accessToken.endsWith("a") ? "b" : "a");

        // when & then
        assertThatThrownBy(() -> provider.parseAccessToken(tampered))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    @DisplayName("다른 namespace로 발급된 토큰은 다른 namespace 값으로 파싱된다")
    void preservesNamespaceAcrossTypes() {
        // given
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));

        // when
        String storeOperatorToken = provider.generateAccessToken(TokenNamespace.STORE_OPERATOR, 5L);
        ParsedToken parsed = provider.parseAccessToken(storeOperatorToken);

        // then
        assertThat(parsed.namespace()).isEqualTo(TokenNamespace.STORE_OPERATOR);
    }

    private Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
