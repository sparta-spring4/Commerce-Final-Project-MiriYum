package com.miriyum.global.sse;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** SSE 재연결 cursor와 Valkey routing key를 독립 secret의 HMAC으로 보호한다. */
@Component
public class SseCursorCodec {

    static final String CONTRACT_VERSION = "sse-runtime-v1";
    private static final int MAXIMUM_CURSOR_LENGTH = 512;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SseRuntimeProperties properties;

    public SseCursorCodec(SseRuntimeProperties properties) {
        this.properties = properties;
    }

    public String encode(SseStreamScope scope, long watermark) {
        if (scope == null || watermark < 0) {
            throw new IllegalArgumentException("SSE cursor boundary is invalid");
        }
        byte[] key = properties.requireRuntime().cursorKey();
        String payload = String.join("\n",
                CONTRACT_VERSION,
                scope.audience().name(),
                Long.toString(scope.accountId()),
                scope.storeId() == null ? "" : scope.storeId().toString(),
                Long.toString(watermark));
        String envelope = payload + "\n" + hexHmac(key, "cursor\n" + payload);
        String cursor = ENCODER.encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        if (cursor.length() > MAXIMUM_CURSOR_LENGTH) {
            throw new IllegalStateException("SSE cursor exceeded contract length");
        }
        return cursor;
    }

    public long decode(SseStreamScope expectedScope, String cursor) {
        byte[] key = properties.requireRuntime().cursorKey();
        if (expectedScope == null
                || cursor == null
                || cursor.isBlank()
                || cursor.length() > MAXIMUM_CURSOR_LENGTH
                || !cursor.matches("[A-Za-z0-9_-]+")) {
            throw invalidCursor();
        }
        try {
            String[] fields = new String(DECODER.decode(cursor), StandardCharsets.UTF_8)
                    .split("\\n", -1);
            if (fields.length != 6) {
                throw invalidCursor();
            }
            String payload = String.join("\n", fields[0], fields[1], fields[2], fields[3], fields[4]);
            byte[] expectedSignature = hexHmac(key, "cursor\n" + payload)
                    .getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(
                    fields[5].getBytes(StandardCharsets.US_ASCII), expectedSignature)
                    || !CONTRACT_VERSION.equals(fields[0])
                    || !expectedScope.audience().name().equals(fields[1])
                    || !Long.toString(expectedScope.accountId()).equals(fields[2])
                    || !(expectedScope.storeId() == null ? "" : expectedScope.storeId().toString())
                    .equals(fields[3])) {
                throw invalidCursor();
            }
            long watermark = Long.parseLong(fields[4]);
            if (watermark < 0) {
                throw invalidCursor();
            }
            return watermark;
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    public String routingKey(SseWakeUpTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("wake-up target is required");
        }
        return hexHmac(
                properties.requireRuntime().cursorKey(),
                "route\n" + target.canonicalValue());
    }

    private static String hexHmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    private static ServiceException invalidCursor() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
