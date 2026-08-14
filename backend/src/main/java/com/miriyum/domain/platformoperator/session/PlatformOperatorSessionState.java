package com.miriyum.domain.platformoperator.session;

import java.time.Instant;

public record PlatformOperatorSessionState(
        Long accountId,
        String sessionHash,
        String refreshTokenId,
        String refreshTokenHash,
        Instant loginAt,
        Instant lastActivityAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        long authorityVersion,
        long sessionVersion,
        boolean passwordChangeRequired) {

    public PlatformOperatorSessionState touch(Instant now, Instant nextIdleExpiresAt) {
        return new PlatformOperatorSessionState(accountId, sessionHash, refreshTokenId, refreshTokenHash,
                loginAt, now, nextIdleExpiresAt, absoluteExpiresAt, authorityVersion, sessionVersion,
                passwordChangeRequired);
    }

    public PlatformOperatorSessionState rotate(
            String nextTokenId, String nextTokenHash, Instant now, Instant nextIdleExpiresAt) {
        return new PlatformOperatorSessionState(accountId, sessionHash, nextTokenId, nextTokenHash,
                loginAt, now, nextIdleExpiresAt, absoluteExpiresAt, authorityVersion, sessionVersion,
                passwordChangeRequired);
    }
}
