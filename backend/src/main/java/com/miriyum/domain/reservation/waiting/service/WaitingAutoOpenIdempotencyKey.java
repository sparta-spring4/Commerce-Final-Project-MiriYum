package com.miriyum.domain.reservation.waiting.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class WaitingAutoOpenIdempotencyKey {

    private static final String NAMESPACE = "WAITING_AUTO_OPEN";

    private WaitingAutoOpenIdempotencyKey() {
    }

    public static String from(long storeId, String businessIntervalKey, long settingsVersion) {
        if (storeId <= 0 || settingsVersion <= 0
                || businessIntervalKey == null || businessIntervalKey.isBlank()) {
            throw new IllegalArgumentException("auto-open idempotency source is invalid");
        }
        String canonical = String.join(
                "|",
                NAMESPACE,
                Long.toString(storeId),
                businessIntervalKey,
                Long.toString(settingsVersion));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
