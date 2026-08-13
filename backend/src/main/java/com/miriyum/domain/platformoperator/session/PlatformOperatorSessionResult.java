package com.miriyum.domain.platformoperator.session;

public record PlatformOperatorSessionResult(Status status, PlatformOperatorSessionState state) {
    public enum Status {
        CREATED,
        VALID,
        ROTATED,
        INVALID,
        EXPIRED,
        REUSED
    }

    public static PlatformOperatorSessionResult of(Status status) {
        return new PlatformOperatorSessionResult(status, null);
    }
}
