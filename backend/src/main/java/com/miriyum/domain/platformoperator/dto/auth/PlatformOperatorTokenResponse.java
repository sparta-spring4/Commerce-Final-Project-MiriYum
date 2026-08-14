package com.miriyum.domain.platformoperator.dto.auth;

import java.time.Instant;

public record PlatformOperatorTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        boolean passwordChangeRequired,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt) {

    public static PlatformOperatorTokenResponse from(PlatformOperatorTokenResult result) {
        return new PlatformOperatorTokenResponse(result.accessToken(), result.tokenType(), result.expiresIn(),
                result.passwordChangeRequired(), result.idleExpiresAt(), result.absoluteExpiresAt());
    }
}
