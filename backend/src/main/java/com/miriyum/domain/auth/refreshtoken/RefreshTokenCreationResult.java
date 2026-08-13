package com.miriyum.domain.auth.refreshtoken;

/** Refresh Token family 생성 시 계정 세대 검증 결과다. */
public record RefreshTokenCreationResult(Status status) {

    public enum Status {
        CREATED,
        SESSION_EPOCH_CHANGED
    }
}
