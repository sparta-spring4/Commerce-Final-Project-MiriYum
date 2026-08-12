package com.miriyum.domain.auth.social.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoSignUpTicket;
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

/** 카카오 원문 식별자 없이 첫 가입을 이어갈 수 있는 5분 유효 가입 티켓을 발급·검증한다. */
public class KakaoSignUpTicketService {

    private static final String AUDIENCE = "kakao-sign-up-ticket";
    private static final String CLAIM_NAMESPACE = "namespace";
    private static final String CLAIM_FINGERPRINT = "providerSubjectFingerprint";
    private static final String CLAIM_FINGERPRINT_KEY_VERSION = "fingerprintKeyVersion";
    private static final Duration VALIDITY = Duration.ofMinutes(5);

    private final SecretKey key;
    private final String issuer;
    private final Clock clock;

    public KakaoSignUpTicketService(String secret, String issuer, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.clock = clock;
    }

    public String create(
            TokenNamespace namespace,
            String fingerprintKeyVersion,
            String providerSubjectFingerprint
    ) {
        if (fingerprintKeyVersion == null || fingerprintKeyVersion.isBlank()
                || providerSubjectFingerprint == null || providerSubjectFingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint key version and value must not be blank");
        }
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_NAMESPACE, namespace.value())
                .claim(CLAIM_FINGERPRINT_KEY_VERSION, fingerprintKeyVersion)
                .claim(CLAIM_FINGERPRINT, providerSubjectFingerprint)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(VALIDITY)))
                .signWith(key)
                .compact();
    }

    public KakaoSignUpTicket parse(String ticket) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(ticket)
                    .getPayload();
            if (!issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(AUDIENCE)) {
                throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
            }
            TokenNamespace namespace = TokenNamespace.fromValue(claims.get(CLAIM_NAMESPACE, String.class));
            String fingerprintKeyVersion = claims.get(CLAIM_FINGERPRINT_KEY_VERSION, String.class);
            String fingerprint = claims.get(CLAIM_FINGERPRINT, String.class);
            if (fingerprintKeyVersion == null || fingerprintKeyVersion.isBlank()
                    || fingerprint == null || fingerprint.isBlank()) {
                throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
            }
            return new KakaoSignUpTicket(namespace, fingerprint, fingerprintKeyVersion);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }
    }
}
