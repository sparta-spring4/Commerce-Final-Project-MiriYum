package com.miriyum.domain.platformoperator.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class PlatformOperatorSessionPolicy {
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    public static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);

    public Instant idleExpiresAt(Instant activityAt) {
        return Objects.requireNonNull(activityAt).plus(IDLE_TIMEOUT);
    }

    public Instant absoluteExpiresAt(Instant loginAt) {
        return Objects.requireNonNull(loginAt).plus(ABSOLUTE_TIMEOUT);
    }

    public Instant nextExpiresAt(Instant activityAt, Instant absoluteExpiresAt) {
        Instant idle = idleExpiresAt(activityAt);
        return idle.isBefore(absoluteExpiresAt) ? idle : absoluteExpiresAt;
    }

    public boolean isExpired(Instant now, Instant expiresAt) {
        return !Objects.requireNonNull(now).isBefore(Objects.requireNonNull(expiresAt));
    }
}
