package com.miriyum.domain.platformoperator.dto.audit;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import jakarta.validation.constraints.NotNull;

public record PlatformOperatorAuditCorrectionRequest(
        @NotNull PlatformOperatorAuditReason reason,
        PlatformOperatorAuditAction correctedAction,
        PlatformOperatorAuditOutcome correctedOutcome,
        String correctedTargetType,
        String correctedTargetId,
        PlatformOperatorAuditReason correctedReason
) {
    public PlatformOperatorAuditCorrectionRequest {
        if (reason != null && reason != PlatformOperatorAuditReason.RECORD_CORRECTION) {
            throw new IllegalArgumentException("correction reason must be RECORD_CORRECTION");
        }
        if (correctedAction == null && correctedOutcome == null && correctedTargetType == null
                && correctedTargetId == null && correctedReason == null) {
            throw new IllegalArgumentException("at least one correction field is required");
        }
    }
}
