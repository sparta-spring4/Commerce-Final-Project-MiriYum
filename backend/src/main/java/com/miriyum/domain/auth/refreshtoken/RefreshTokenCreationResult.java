package com.miriyum.domain.auth.refreshtoken;

/** Refresh Token family ?? ? ?? ?? ?? ?? ???. */
public record RefreshTokenCreationResult(Status status) {

    public enum Status {
        CREATED,
        SESSION_EPOCH_CHANGED
    }
}
