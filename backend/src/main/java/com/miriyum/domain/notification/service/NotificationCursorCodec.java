package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.config.NotificationHistorySettings;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** 알림 이력 keyset 경계를 소비자 범위에 묶어 HMAC으로 보호한다. */
@Component
public class NotificationCursorCodec {

    static final String CONTRACT_VERSION = "notification-history-v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAXIMUM_CURSOR_LENGTH = 512;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final NotificationHistorySettings settings;

    public NotificationCursorCodec(NotificationHistorySettings settings) {
        this.settings = settings;
    }

    public String encode(long consumerAccountId, Boundary boundary) {
        return encode(CONTRACT_VERSION, consumerAccountId, boundary);
    }

    /** secret 누락 여부를 page 내용과 무관하게 endpoint 진입 시 확인한다. */
    public void requireAvailable() {
        settings.requireCursorKey();
    }

    String encodeForTest(String version, long consumerAccountId, Boundary boundary) {
        return encode(version, consumerAccountId, boundary);
    }

    String encodeRawForTest(String boundaryPayload, long consumerAccountId) {
        byte[] key = settings.requireCursorKey();
        String payload = boundaryPayload + "\n" + scope(key, consumerAccountId);
        String envelope = payload + "\n" + hexHmac(key, "cursor\n" + payload);
        return ENCODER.encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
    }

    public Boundary decode(long consumerAccountId, String cursor) {
        byte[] key = settings.requireCursorKey();
        if (cursor == null
                || cursor.isBlank()
                || cursor.length() > MAXIMUM_CURSOR_LENGTH
                || !cursor.matches("[A-Za-z0-9_-]+")) {
            throw invalidCursor();
        }
        try {
            String envelope = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            String[] fields = envelope.split("\\n", -1);
            if (fields.length != 6) {
                throw invalidCursor();
            }
            String payload = String.join("\n", fields[0], fields[1], fields[2], fields[3], fields[4]);
            byte[] suppliedSignature = fields[5].getBytes(StandardCharsets.US_ASCII);
            byte[] expectedSignature = hexHmac(key, "cursor\n" + payload)
                    .getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(suppliedSignature, expectedSignature)
                    || !CONTRACT_VERSION.equals(fields[0])
                    || !scope(key, consumerAccountId).equals(fields[4])) {
                throw invalidCursor();
            }
            long epochSecond = Long.parseLong(fields[1]);
            int nano = Integer.parseInt(fields[2]);
            long notificationId = Long.parseLong(fields[3]);
            if (notificationId <= 0) {
                throw invalidCursor();
            }
            return new Boundary(Instant.ofEpochSecond(epochSecond, nano), notificationId);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private String encode(String version, long consumerAccountId, Boundary boundary) {
        byte[] key = settings.requireCursorKey();
        if (consumerAccountId <= 0 || boundary == null || boundary.notificationId() <= 0) {
            throw new IllegalArgumentException("cursor boundary must contain positive IDs");
        }
        String payload = String.join(
                "\n",
                version,
                Long.toString(boundary.occurredAt().getEpochSecond()),
                Integer.toString(boundary.occurredAt().getNano()),
                Long.toString(boundary.notificationId()),
                scope(key, consumerAccountId)
        );
        String envelope = payload + "\n" + hexHmac(key, "cursor\n" + payload);
        String cursor = ENCODER.encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        if (cursor.length() > MAXIMUM_CURSOR_LENGTH) {
            throw new IllegalStateException("notification cursor exceeded contract length");
        }
        return cursor;
    }

    private static String scope(byte[] key, long consumerAccountId) {
        return hexHmac(key, "consumer\n" + consumerAccountId);
    }

    private static String hexHmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    private static ServiceException invalidCursor() {
        return new ServiceException(NotificationErrorCode.INVALID_HISTORY_CURSOR);
    }

    public record Boundary(Instant occurredAt, long notificationId) {
        public Boundary {
            if (occurredAt == null) {
                throw new IllegalArgumentException("occurredAt must not be null");
            }
        }
    }
}
