package com.miriyum.domain.auth.social.dto;

import java.util.Objects;

public record KakaoIdentityFingerprint(
        String keyVersion,
        String value
) {

    public KakaoIdentityFingerprint {
        keyVersion = Objects.requireNonNull(keyVersion, "keyVersion must not be null");
        value = Objects.requireNonNull(value, "value must not be null");
        if (keyVersion.isBlank() || value.isBlank()) {
            throw new IllegalArgumentException("fingerprint keyVersion and value must not be blank");
        }
    }
}
