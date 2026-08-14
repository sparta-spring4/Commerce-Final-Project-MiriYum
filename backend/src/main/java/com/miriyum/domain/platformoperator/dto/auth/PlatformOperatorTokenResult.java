package com.miriyum.domain.platformoperator.dto.auth;

import java.time.Instant;

public record PlatformOperatorTokenResult(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        boolean passwordChangeRequired,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt) {
}
