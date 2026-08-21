package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.config.WaitingHistoryProperties;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.Scope;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** 소비자·필터·정렬 경계를 HMAC으로 결속한 웨이팅 이력 cursor codec이다. */
@Component
public class WaitingConsumerHistoryCursorCodec {

    static final String CONTRACT_VERSION = "waiting-consumer-history-v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAXIMUM_CURSOR_LENGTH = 512;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final WaitingHistoryProperties properties;

    public WaitingConsumerHistoryCursorCodec(WaitingHistoryProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    /** secret 누락 여부를 page 내용과 cursor 유무에 관계없이 endpoint 진입 시 확인한다. */
    public void requireAvailable() {
        properties.requireCursorKey();
    }

    public String encode(long consumerAccountId, Scope scope, Boundary boundary) {
        byte[] key = properties.requireCursorKey();
        if (consumerAccountId <= 0 || scope == null || boundary == null
                || boundary.waitingTeamId() <= 0) {
            throw new IllegalArgumentException("cursor scope and boundary must be valid");
        }
        String payload = String.join(
                "\n",
                CONTRACT_VERSION,
                Long.toString(boundary.registeredAt().getEpochSecond()),
                Integer.toString(boundary.registeredAt().getNano()),
                Long.toString(boundary.waitingTeamId()),
                scope.name(),
                consumerScope(key, consumerAccountId));
        String envelope = payload + "\n" + hexHmac(key, "cursor\n" + payload);
        String cursor = ENCODER.encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        if (cursor.length() > MAXIMUM_CURSOR_LENGTH) {
            throw new IllegalStateException("waiting history cursor exceeded contract length");
        }
        return cursor;
    }

    public Boundary decode(long consumerAccountId, Scope scope, String cursor) {
        byte[] key = properties.requireCursorKey();
        if (consumerAccountId <= 0 || scope == null || cursor == null
                || cursor.isBlank() || cursor.length() > MAXIMUM_CURSOR_LENGTH
                || !cursor.matches("[A-Za-z0-9_-]+")) {
            throw invalidCursor();
        }
        try {
            String envelope = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            String[] fields = envelope.split("\\n", -1);
            if (fields.length != 7) {
                throw invalidCursor();
            }
            String payload = String.join(
                    "\n", fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
            if (!MessageDigest.isEqual(
                    fields[6].getBytes(StandardCharsets.US_ASCII),
                    hexHmac(key, "cursor\n" + payload).getBytes(StandardCharsets.US_ASCII))
                    || !CONTRACT_VERSION.equals(fields[0])
                    || !scope.name().equals(fields[4])
                    || !consumerScope(key, consumerAccountId).equals(fields[5])) {
                throw invalidCursor();
            }
            long waitingTeamId = Long.parseLong(fields[3]);
            if (waitingTeamId <= 0) {
                throw invalidCursor();
            }
            return new Boundary(
                    Instant.ofEpochSecond(Long.parseLong(fields[1]), Integer.parseInt(fields[2])),
                    waitingTeamId);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private static String consumerScope(byte[] key, long consumerAccountId) {
        return hexHmac(key, "consumer\n" + consumerAccountId);
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
        return new ServiceException(ReservationErrorCode.WAITING_HISTORY_CURSOR_INVALID);
    }

    public record Boundary(Instant registeredAt, long waitingTeamId) {
        public Boundary {
            Objects.requireNonNull(registeredAt);
        }
    }
}
