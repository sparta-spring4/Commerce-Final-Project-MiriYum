package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

public final class NotificationPayloadFingerprint {

    private static final DateTimeFormatter UTC_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private NotificationPayloadFingerprint() {
    }

    public static String of(NotificationSourceEventV1 event) {
        String canonical = "{"
                + "\"contractVersion\":\"" + NotificationSourceEventV1.CONTRACT_VERSION + "\","
                + "\"expiresAt\":" + nullableDateTime(event.expiresAt()) + ","
                + "\"occurredAt\":\"" + utc(event.occurredAt()) + "\","
                + "\"recipientRelationVersion\":\"" + event.recipientRelationVersion() + "\","
                + "\"scheduledAt\":\"" + utc(event.scheduledAt()) + "\","
                + "\"sourceState\":\"" + escapeJcsString(event.sourceState()) + "\","
                + "\"timingPolicyVersion\":" + nullableLong(event.timingPolicyVersion())
                + "}";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullableDateTime(java.time.OffsetDateTime value) {
        return value == null ? "null" : "\"" + utc(value) + "\"";
    }

    private static String nullableLong(Long value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String utc(java.time.OffsetDateTime value) {
        return UTC_SECONDS.format(value.withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static String escapeJcsString(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                throw new IllegalArgumentException("sourceState contains an unpaired surrogate");
            }
            switch (codePoint) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\f' -> escaped.append("\\f");
                case '\r' -> escaped.append("\\r");
                default -> {
                    if (codePoint < 0x20) {
                        escaped.append(String.format("\\u%04x", codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return escaped.toString();
    }
}
