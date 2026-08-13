package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Instant;
import java.util.Objects;

/** Valkey에 저장할 Refresh Token family의 현재 상태다. */
public record RefreshTokenState(
        TokenNamespace namespace,
        Long accountId,
        String familyId,
        String currentTokenId,
        String currentTokenHash,
        Instant familyExpiresAt,
        Instant lastRotatedAt,
        Status status
) {

    public RefreshTokenState {
        Objects.requireNonNull(namespace, "namespace");
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive");
        }
        if (isBlank(familyId) || isBlank(currentTokenId) || isBlank(currentTokenHash)) {
            throw new IllegalArgumentException("Refresh Token state identifiers must not be blank");
        }
        Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");
        Objects.requireNonNull(lastRotatedAt, "lastRotatedAt");
        if (!familyExpiresAt.isAfter(lastRotatedAt)) {
            throw new IllegalArgumentException("familyExpiresAt must be after lastRotatedAt");
        }
        Objects.requireNonNull(status, "status");
    }

    public enum Status {
        ACTIVE,
        REVOKED
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
