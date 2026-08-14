package com.miriyum.domain.platformoperator.session;

public record PlatformOperatorSessionProof(
        Long accountId,
        String sessionHash,
        String refreshTokenId,
        String refreshTokenHash,
        long authorityVersion,
        long sessionVersion) {
}
