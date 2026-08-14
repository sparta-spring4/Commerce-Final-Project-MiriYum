package com.miriyum.domain.platformoperator.dto.authorization;

import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import java.util.Objects;

public record HighRiskCommandRequest(
        PlatformOperatorPrincipal principal,
        PlatformOperatorPermission requiredPermission,
        AdminCaseType caseType,
        String caseId,
        long caseVersion,
        AdminCommandPurpose purpose,
        AdminTargetType targetType,
        String targetId,
        String approval,
        String correlationId
) {
    public HighRiskCommandRequest {
        Objects.requireNonNull(principal);
        Objects.requireNonNull(requiredPermission);
        Objects.requireNonNull(caseType);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(targetType);
        requireText(caseId, "caseId");
        requireText(targetId, "targetId");
        requireText(approval, "approval");
        requireText(correlationId, "correlationId");
        if (caseVersion < 1) throw new IllegalArgumentException("caseVersion must be positive");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
