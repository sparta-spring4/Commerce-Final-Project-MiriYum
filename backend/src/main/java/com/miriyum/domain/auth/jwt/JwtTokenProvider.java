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
 * 계정 유형별 namespace를 담은 Access/Refresh JWT를 발급·검증한다.
 * Access JWT는 stateless로 검증하고, 고도화 Refresh JWT의 현재 상태는 호출 계층이 Valkey에서 확인한다.
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_NAMESPACE = "namespace";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String CLAIM_FAMILY_ID = "familyId";
    private static final String CLAIM_TOKEN_ID = "tokenId";

    // Access Token은 무상태 검증이라 발급 후 서버가 되돌릴 수 없다. revokeAll이 session epoch를
    // 올려도 이미 발급된 Access Token은 만료까지 유효하므로, 그 노출 창을 15분으로 제한한다(AUTH-007).
    private static final Duration ACCESS_TOKEN_VALIDITY = Duration.ofMinutes(15);
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

    public long getRefreshTokenValiditySeconds() {
        return REFRESH_TOKEN_VALIDITY.toSeconds();
    }

    public String generateAccessToken(TokenNamespace namespace, Long accountId) {
        return generateToken(namespace, accountId, TokenType.ACCESS, ACCESS_TOKEN_VALIDITY, null, null);
    }

    public String generateRefreshToken(TokenNamespace namespace, Long accountId, String familyId, String tokenId) {
        return generateToken(namespace, accountId, TokenType.REFRESH, REFRESH_TOKEN_VALIDITY, familyId, tokenId);
    }

    public ParsedToken parseAccessToken(String token) {
        Claims claims = parseClaims(token, AuthErrorCode.ACCESS_TOKEN_EXPIRED, AuthErrorCode.ACCESS_TOKEN_INVALID);
        requireTokenType(claims, TokenType.ACCESS, AuthErrorCode.ACCESS_TOKEN_INVALID);
        return toParsedToken(claims, AuthErrorCode.ACCESS_TOKEN_INVALID);
    }

    public ParsedToken parseRefreshToken(String token) {
        Claims claims = parseClaims(token, AuthErrorCode.REFRESH_TOKEN_INVALID, AuthErrorCode.REFRESH_TOKEN_INVALID);
        requireTokenType(claims, TokenType.REFRESH, AuthErrorCode.REFRESH_TOKEN_INVALID);
        ParsedToken parsedToken = toParsedToken(claims, AuthErrorCode.REFRESH_TOKEN_INVALID);
        if (parsedToken.familyId() == null || parsedToken.familyId().isBlank()
                || parsedToken.tokenId() == null || parsedToken.tokenId().isBlank()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        return parsedToken;
    }

    /** 만료·폐기·존재하지 않는 Refresh Token으로도 로그아웃 결과를 멱등하게 만든다. */
    public ParsedToken parseRefreshTokenForLogout(String token) {
        try {
            return parseRefreshToken(token);
        } catch (ServiceException exception) {
            if (exception.getErrorCode() == AuthErrorCode.REFRESH_TOKEN_INVALID) {
                return null;
            }
            throw exception;
        }
    }

    private String generateToken(
            TokenNamespace namespace,
            Long accountId,
            TokenType tokenType,
            Duration validity,
            String familyId,
            String tokenId
    ) {
        Instant now = clock.instant();
        String subject = namespace.value() + ":" + accountId;

        return Jwts.builder()
                .issuer(issuer)
                .audience().add(namespace.value()).and()
                .subject(subject)
                .claim(CLAIM_NAMESPACE, namespace.value())
                .claim(CLAIM_TOKEN_TYPE, tokenType.name())
                .claim(CLAIM_FAMILY_ID, familyId)
                .claim(CLAIM_TOKEN_ID, tokenId)
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

    private ParsedToken toParsedToken(Claims claims, ErrorCode invalidCode) {
        String namespaceValue = claims.get(CLAIM_NAMESPACE, String.class);
        TokenNamespace namespace;
        try {
            namespace = TokenNamespace.fromValue(namespaceValue);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(invalidCode);
        }

        String subject = claims.getSubject();
        int separatorIndex = subject == null ? -1 : subject.lastIndexOf(':');
        if (separatorIndex < 0) {
            throw new ServiceException(invalidCode);
        }

        try {
            Long accountId = Long.valueOf(subject.substring(separatorIndex + 1));
            return new ParsedToken(
                    namespace,
                    accountId,
                    claims.get(CLAIM_FAMILY_ID, String.class),
                    claims.get(CLAIM_TOKEN_ID, String.class));
        } catch (NumberFormatException exception) {
            throw new ServiceException(invalidCode);
        }
    }
}
