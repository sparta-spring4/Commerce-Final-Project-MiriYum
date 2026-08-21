package com.miriyum.domain.store.evidence.dto;

import java.util.Arrays;

/** Raw evidence boundary without storage identity, key, or URL. */
public record BusinessRegistrationEvidenceContent(String contentType, byte[] bytes) {
    public BusinessRegistrationEvidenceContent {
        if (contentType == null || contentType.isBlank() || bytes == null) {
            throw new IllegalArgumentException("evidence content is required");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
