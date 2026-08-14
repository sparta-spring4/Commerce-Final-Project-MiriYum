package com.miriyum.domain.platformoperator.dto.authorization;

import java.time.Instant;

public record ReauthenticationApprovalResponse(String approval, Instant expiresAt) {
    public static ReauthenticationApprovalResponse from(ReauthenticationApprovalResult result) {
        return new ReauthenticationApprovalResponse(result.approval(), result.expiresAt());
    }
}
