package com.miriyum.domain.store.evidence;

import java.util.Arrays;

public record ValidatedBusinessRegistrationEvidence(
        String contentType,
        byte[] bytes,
        String sha256
) {
    public ValidatedBusinessRegistrationEvidence {
        if (contentType == null || contentType.isBlank()
                || bytes == null || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("validated evidence fields are required");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
