package com.miriyum.domain.platformoperator.dto.authorization;

import java.time.Instant;

/** 응답 한 번에만 노출되는 승인 원문과 고정 만료 시각이다. */
public record ReauthenticationApprovalResult(String approval, Instant expiresAt) {
    @Override
    public String toString() {
        return "ReauthenticationApprovalResult[approval=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
