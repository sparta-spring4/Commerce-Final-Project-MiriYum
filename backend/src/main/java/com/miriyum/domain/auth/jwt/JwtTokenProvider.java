package com.miriyum.domain.auth.jwt;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 계정 유형별 namespace를 담은 stateless Access/Refresh JWT를 발급·검증한다.
 * 1차 MVP는 서버·Valkey에 토큰 상태를 저장하지 않는다 ({@code ADR-006}).
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_NAMESPACE = "namespace";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";

    private static final Duration ACCESS_TOKEN_VALIDITY = Duration.ofHours(1);
    private static final Duration REFRESH_TOKEN_VALIDITY = Duration.ofDays(14);

    private final SecretKey key;
    private final String issuer;
    private final Clock clock;

    public JwtTokenProvider(
            @Value("${miriyum.jwt.secret}") String secret,
            @Value("${miriyum.jwt.issuer}") String issuer,
            Clock clock
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.clock = clock;
    }

    public long getAccessTokenValiditySeconds() {
        return ACCESS_TOKEN_VALIDITY.toSeconds();
    }

    public String generateAccessToken(TokenNamespace namespace, Long accountId) {
        return generateToken(namespace, accountId, TokenType.ACCESS, ACCESS_TOKEN_VALIDITY);
    }

    public String generateRefreshToken(TokenNamespace namespace, Long accountId) {
        return generateToken(namespace, accountId, TokenType.REFRESH, REFRESH_TOKEN_VALIDITY);
    }

    public ParsedToken parseAccessToken(String token) {
        Claims claims = parseClaims(token, AuthErrorCode.ACCESS_TOKEN_EXPIRED, AuthErrorCode.ACCESS_TOKEN_INVALID);
        requireTokenType(claims, TokenType.ACCESS, AuthErrorCode.ACCESS_TOKEN_INVALID);
        return toParsedToken(claims);
    }

    public ParsedToken parseRefreshToken(String token) {
        Claims claims = parseClaims(token, AuthErrorCode.REFRESH_TOKEN_INVALID, AuthErrorCode.REFRESH_TOKEN_INVALID);
        requireTokenType(claims, TokenType.REFRESH, AuthErrorCode.REFRESH_TOKEN_INVALID);
        return toParsedToken(claims);
    }

    private String generateToken(TokenNamespace namespace, Long accountId, TokenType tokenType, Duration validity) {
        Instant now = clock.instant();
        String subject = namespace.value() + ":" + accountId;

        return Jwts.builder()
                .issuer(issuer)
                .audience().add(namespace.value()).and()
                .subject(subject)
                .claim(CLAIM_NAMESPACE, namespace.value())
                .claim(CLAIM_TOKEN_TYPE, tokenType.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .signWith(key)
                .compact();
    }

    private Claims parseClaims(String token, ErrorCode expiredCode, ErrorCode invalidCode) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException exception) {
            throw new ServiceException(expiredCode);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new ServiceException(invalidCode);
        }
    }

    private void requireTokenType(Claims claims, TokenType expected, ErrorCode onMismatch) {
        String actual = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (!expected.name().equals(actual)) {
            throw new ServiceException(onMismatch);
        }
    }

    private ParsedToken toParsedToken(Claims claims) {
        String namespaceValue = claims.get(CLAIM_NAMESPACE, String.class);
        TokenNamespace namespace;
        try {
            namespace = TokenNamespace.fromValue(namespaceValue);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID);
        }

        String subject = claims.getSubject();
        int separatorIndex = subject == null ? -1 : subject.lastIndexOf(':');
        if (separatorIndex < 0) {
            throw new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID);
        }

        try {
            Long accountId = Long.valueOf(subject.substring(separatorIndex + 1));
            return new ParsedToken(namespace, accountId);
        } catch (NumberFormatException exception) {
            throw new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID);
        }
    }
}
