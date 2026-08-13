package com.miriyum.domain.auth.refreshtoken;

/** Refresh Token 회전의 원자적 결과다. */
public record RefreshTokenRotationResult(Status status) {

    public RefreshTokenRotationResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    public enum Status {
        ROTATED,
        NOT_FOUND,
        REUSED
    }

    public boolean rotated() {
        return status == Status.ROTATED;
    }
}
