package com.miriyum.domain.auth.jwt;

/** 플랫폼 운영자 JWT를 중앙 세션과 현재 계정 버전에 결속하는 최소 클레임이다. */
public record SessionTokenClaims(
        String sessionId,
        long authorityVersion,
        long sessionVersion,
        boolean passwordChangeRequired
) {

    public SessionTokenClaims {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (authorityVersion < 1 || sessionVersion < 1) {
            throw new IllegalArgumentException("session versions must be positive");
        }
    }
}
