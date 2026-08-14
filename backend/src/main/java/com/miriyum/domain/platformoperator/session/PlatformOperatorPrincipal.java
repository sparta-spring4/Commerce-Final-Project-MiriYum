package com.miriyum.domain.platformoperator.session;

public record PlatformOperatorPrincipal(
        Long accountId,
        String email,
        String sessionId,
        long authorityVersion,
        long sessionVersion,
        boolean passwordChangeRequired) {
}
