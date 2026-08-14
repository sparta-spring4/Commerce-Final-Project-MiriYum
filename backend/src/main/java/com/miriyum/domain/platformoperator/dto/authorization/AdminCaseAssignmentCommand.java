package com.miriyum.domain.platformoperator.dto.authorization;

import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import java.time.Instant;
import java.util.Objects;

public record AdminCaseAssignmentCommand(
        AdminCaseType caseType,
        String caseId,
        long caseVersion,
        long operatorId,
        Instant expiresAt
) {
    public AdminCaseAssignmentCommand {
        new AdminCaseAssignmentRequest(caseType, caseId, caseVersion, operatorId);
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public AdminCaseAssignmentRequest key() {
        return new AdminCaseAssignmentRequest(caseType, caseId, caseVersion, operatorId);
    }
}
