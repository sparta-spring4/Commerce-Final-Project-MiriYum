package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenPair;

/** Refresh Token 회전 결과와 성공 시 발급할 토큰을 함께 전달한다. */
public record RefreshTokenRotationAttempt(
        RefreshTokenRotationResult.Status status,
        TokenPair tokenPair
) {

    public RefreshTokenRotationAttempt {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if ((status == RefreshTokenRotationResult.Status.ROTATED) != (tokenPair != null)) {
            throw new IllegalArgumentException("Only a rotated result can contain a token pair");
        }
    }

    public boolean rotated() {
        return status == RefreshTokenRotationResult.Status.ROTATED;
    }

    public boolean reused() {
        return status == RefreshTokenRotationResult.Status.REUSED;
    }
}
