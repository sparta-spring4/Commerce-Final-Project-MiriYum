package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 결제 이력 정렬 경계와 필터를 서명된 불투명 cursor로 변환한다. */
public final class PaymentCursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] secret;
    private final Clock clock;

    public PaymentCursorCodec(String secret, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("payment cursor secret must contain at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public String encode(Instant createdAt, String paymentId, String filterFingerprint) {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        requireText(paymentId, "paymentId");
        requireText(filterFingerprint, "filterFingerprint");
        String payload = createdAt.getEpochSecond() + "," + createdAt.getNano()
                + ":" + paymentId
                + ":" + encodeBytes(filterFingerprint.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = encodeBytes(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + encodeBytes(sign(encodedPayload));
    }

    public Cursor decode(String encodedCursor, String expectedFilterFingerprint) {
        try {
            requireText(encodedCursor, "cursor");
            requireText(expectedFilterFingerprint, "filterFingerprint");
            String[] token = encodedCursor.split("\\.", -1);
            if (token.length != 2 || token[0].isBlank() || token[1].isBlank()) {
                throw invalidCursor();
            }
            byte[] expectedSignature = sign(token[0]);
            byte[] actualSignature = Base64.getUrlDecoder().decode(token[1]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                throw invalidCursor();
            }
            String payload = new String(
                    Base64.getUrlDecoder().decode(token[0]),
                    StandardCharsets.UTF_8
            );
            String[] fields = payload.split(":", 3);
            if (fields.length != 3) {
                throw invalidCursor();
            }
            String[] instantParts = fields[0].split(",", -1);
            if (instantParts.length != 2) {
                throw invalidCursor();
            }
            Instant createdAt = Instant.ofEpochSecond(
                    Long.parseLong(instantParts[0]),
                    Integer.parseInt(instantParts[1])
            );
            String paymentId = fields[1];
            String fingerprint = new String(
                    Base64.getUrlDecoder().decode(fields[2]),
                    StandardCharsets.UTF_8
            );
            if (!MessageDigest.isEqual(
                    fingerprint.getBytes(StandardCharsets.UTF_8),
                    expectedFilterFingerprint.getBytes(StandardCharsets.UTF_8))) {
                throw invalidCursor();
            }
            if (createdAt.isAfter(clock.instant()) || !paymentId.matches("^[1-9][0-9]{0,18}$")) {
                throw invalidCursor();
            }
            return new Cursor(createdAt, paymentId);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }

    private static String encodeBytes(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static ServiceException invalidCursor() {
        return new ServiceException(PaymentErrorCode.INVALID_HISTORY_CURSOR);
    }

    public record Cursor(Instant createdAt, String paymentId) {
    }
}
