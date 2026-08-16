package com.miriyum.domain.auth.qrepoch;

import java.util.regex.Pattern;

/**
 * QR 발급 시점의 계정과 opaque 세대 버전이다. 버전은 equality 비교 전용이며 내부 generation, salt,
 * counter를 노출하지 않는다.
 */
public record ConsumerQrEpochSnapshot(Long accountId, String opaqueVersion) {

    private static final Pattern OPAQUE_VERSION_PATTERN = Pattern.compile("^v1\\.[A-Za-z0-9_-]{43}$");

    public ConsumerQrEpochSnapshot {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive");
        }
        if (opaqueVersion == null || !OPAQUE_VERSION_PATTERN.matcher(opaqueVersion).matches()) {
            throw new IllegalArgumentException("opaqueVersion must be a v1 base64url digest");
        }
    }
}
