package com.miriyum.domain.auth.qrepoch;

import java.util.regex.Pattern;

final class ConsumerQrEpochKey {

    private static final Pattern STORAGE_GENERATION_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private ConsumerQrEpochKey() {
    }

    static String forAccount(String storageGeneration, Long accountId) {
        if (storageGeneration == null
                || !STORAGE_GENERATION_PATTERN.matcher(storageGeneration).matches()
                || accountId == null
                || accountId <= 0) {
            throw new IllegalArgumentException("valid storageGeneration and accountId are required");
        }
        return "auth:qr-epoch:" + storageGeneration + ":consumer:" + accountId;
    }
}
