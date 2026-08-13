package com.miriyum.domain.auth.social.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoOAuthState;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import com.miriyum.global.exception.ServiceException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * 카카오 OAuth 왕복 요청을 보호하는 짧은 수명의 state를 발급·검증한다.
 *
 * <p>로그인 state는 계정 유형만, 연결 state는 현재 로그인한 계정 ID까지 묶는다. 콜백 처리에서
 * 서명·만료·요청 목적을 모두 확인해 다른 계정 유형이나 계정으로의 연결을 막는다.</p>
 */
public class KakaoOAuthStateService {

    private static final String AUDIENCE = "kakao-oauth-state";
    private static final String CLAIM_NAMESPACE = "namespace";
    private static final String CLAIM_PURPOSE = "purpose";
    private static final String CLAIM_ACCOUNT_ID = "accountId";
    private static final Duration VALIDITY = Duration.ofMinutes(5);

    private final SecretKey key;
    private final String issuer;
    private final Clock clock;

    public KakaoOAuthStateService(String secret, String issuer, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.clock = clock;
    }

    public String createLoginState(TokenNamespace namespace) {
        return create(namespace, KakaoOAuthPurpose.LOGIN, null);
    }

    public String createLinkState(TokenNamespace namespace, Long accountId) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive");
        }
        return create(namespace, KakaoOAuthPurpose.LINK, accountId);
    }

    public KakaoOAuthState parse(String state) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(state)
                    .getPayload();

            if (!issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(AUDIENCE)) {
                throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
            }

            TokenNamespace namespace = TokenNamespace.fromValue(claims.get(CLAIM_NAMESPACE, String.class));
            KakaoOAuthPurpose purpose = KakaoOAuthPurpose.valueOf(claims.get(CLAIM_PURPOSE, String.class));
            Long accountId = claims.get(CLAIM_ACCOUNT_ID, Long.class);
            if ((purpose == KakaoOAuthPurpose.LOGIN && accountId != null)
                    || (purpose == KakaoOAuthPurpose.LINK && (accountId == null || accountId <= 0))) {
                throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
            }
            return new KakaoOAuthState(namespace, purpose, accountId);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }
    }

    private String create(TokenNamespace namespace, KakaoOAuthPurpose purpose, Long accountId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_NAMESPACE, namespace.value())
                .claim(CLAIM_PURPOSE, purpose.name())
                .claim(CLAIM_ACCOUNT_ID, accountId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(VALIDITY)))
                .signWith(key)
                .compact();
    }
}
