package com.miriyum.domain.platformoperator.session;

public record PlatformOperatorSessionResult(
        Status status,
        PlatformOperatorSessionState state,
        java.time.Instant idleExpiresAt,
        java.time.Instant absoluteExpiresAt) {
    public PlatformOperatorSessionResult(Status status, PlatformOperatorSessionState state) {
        this(status, state,
                state == null ? null : state.idleExpiresAt(),
                state == null ? null : state.absoluteExpiresAt());
    }
    public enum Status {
        CREATED,
        REPLACED,
        STALE,
        VALID,
        ROTATED,
        INVALID,
        EXPIRED,
        REUSED
    }

    public static PlatformOperatorSessionResult of(Status status) {
        return new PlatformOperatorSessionResult(status, null, null, null);
    }
}
