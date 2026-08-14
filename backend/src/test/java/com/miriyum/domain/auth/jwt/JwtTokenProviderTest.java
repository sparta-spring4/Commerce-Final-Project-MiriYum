package com.miriyum.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
        String refreshToken = provider.generateRefreshToken(TokenNamespace.CONSUMER, 7L, "family-1", "token-1");

        // when & then
        assertThatThrownBy(() -> provider.parseAccessToken(refreshToken))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    @DisplayName("Refresh JWT는 Valkey family와 현재 token 식별자를 보존한다")
    void parsesRefreshIdentityClaims() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));

        String refreshToken = provider.generateRefreshToken(
                TokenNamespace.CONSUMER, 7L, "family-1", "token-1");
        ParsedToken parsed = provider.parseRefreshToken(refreshToken);

        assertThat(parsed.familyId()).isEqualTo("family-1");
        assertThat(parsed.tokenId()).isEqualTo("token-1");
        assertThat(parsed.familyCreatedAt()).isEqualTo(Instant.parse("2026-07-29T00:00:00Z"));
    }

    @Test
    @DisplayName("만료된 Refresh Token은 로그아웃용 파싱에서 없는 상태로 처리한다")
    void treatsExpiredRefreshTokenAsAbsentForLogout() {
        JwtTokenProvider issuingProvider =
                new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));
        String refreshToken = issuingProvider.generateRefreshToken(
                TokenNamespace.CONSUMER, 7L, "family-1", "token-1");
        JwtTokenProvider verifyingProvider =
                new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-08-13T00:00:00Z"));

        assertThat(verifyingProvider.parseRefreshTokenForLogout(refreshToken)).isNull();
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

    @Test
    @DisplayName("플랫폼 운영자 Access JWT는 중앙 세션과 계정 버전 결속을 보존한다")
    void parsesPlatformOperatorSessionClaims() {
        JwtTokenProvider provider =
                new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));
        SessionTokenClaims claims = new SessionTokenClaims("session-id", 7L, 11L, true);

        String accessToken = provider.generateAccessToken(
                TokenNamespace.PLATFORM_OPERATOR, 42L, claims);
        ParsedToken parsed = provider.parseAccessToken(accessToken);

        assertThat(parsed.namespace()).isEqualTo(TokenNamespace.PLATFORM_OPERATOR);
        assertThat(parsed.accountId()).isEqualTo(42L);
        assertThat(parsed.sessionClaims()).isEqualTo(claims);
    }

    @Test
    @DisplayName("일반 사용자 토큰에는 플랫폼 운영자 세션 결속을 추가하지 않는다")
    void consumerTokenHasNoPlatformOperatorSessionClaims() {
        JwtTokenProvider provider =
                new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));

        ParsedToken parsed = provider.parseAccessToken(
                provider.generateAccessToken(TokenNamespace.CONSUMER, 42L));

        assertThat(parsed.sessionClaims()).isNull();
    }

    @Test
    void rejectsSignedTokenWithWrongIssuerAudienceOrSubjectNamespace() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, ISSUER, fixedClock("2026-07-29T00:00:00Z"));

        assertInvalid(provider, forged("other-issuer", "consumer", "consumer:42", "consumer"));
        assertInvalid(provider, forged(ISSUER, "store-operator", "consumer:42", "consumer"));
        assertInvalid(provider, forged(ISSUER, "consumer", "store-operator:42", "consumer"));
        assertInvalid(provider, forged(ISSUER, "consumer", "consumer:unexpected:42", "consumer"));
    }

    private void assertInvalid(JwtTokenProvider provider, String token) {
        assertThatThrownBy(() -> provider.parseAccessToken(token))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_INVALID);
    }

    private String forged(String issuer, String audience, String subject, String namespace) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return Jwts.builder().issuer(issuer).audience().add(audience).and().subject(subject)
                .claim("namespace", namespace).claim("tokenType", "ACCESS")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(900)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
