package com.miriyum.domain.reservation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Reservation 체크인용 256-bit opaque credential 생성과 SHA-256 digest를 담당한다. */
@Service
public class ReservationCheckInQrTokenService {

    private static final String PREFIX = "rqg_v1_";
    private static final int ENTROPY_BYTES = 32;
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("^rqg_v1_[A-Za-z0-9_-]{43}$");

    private final SecureRandom secureRandom;

    public ReservationCheckInQrTokenService() {
        this(new SecureRandom());
    }

    ReservationCheckInQrTokenService(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    /** 새 raw credential과 그 전체 문자열의 SHA-256 digest를 함께 생성한다. */
    public GeneratedToken generate() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        secureRandom.nextBytes(entropy);
        String rawToken = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return new GeneratedToken(rawToken, digest(rawToken));
    }

    /** strict versioned QR credential을 검증하고 전체 문자열의 SHA-256 digest를 반환한다. */
    public byte[] digest(String rawToken) {
        if (rawToken == null || !TOKEN_PATTERN.matcher(rawToken).matches()) {
            throw new IllegalArgumentException("rawToken must be a v1 256-bit base64url token");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    /** raw token은 발급 응답 조립까지만 전달하고 digest는 영속·스캔 비교에 사용한다. */
    public record GeneratedToken(String rawToken, byte[] digest) {

        public GeneratedToken {
            rawToken = Objects.requireNonNull(rawToken);
            if (digest == null || digest.length != ENTROPY_BYTES) {
                throw new IllegalArgumentException("digest must contain 32 bytes");
            }
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }
}
