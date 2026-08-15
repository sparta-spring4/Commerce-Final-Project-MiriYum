package com.miriyum.domain.platformoperator.dto.audit;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import java.time.Instant;

public record PlatformOperatorAuditSearchRequest(
        int page,
        int size,
        String source,
        PlatformOperatorAuditAction action,
        PlatformOperatorAuditOutcome outcome,
        String actorOperatorId,
        String targetType,
        String targetId,
        Instant occurredFrom,
        Instant occurredTo,
        String originalEventKey
) {
    public PlatformOperatorAuditSearchRequest {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("invalid page request");
        }
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw new IllegalArgumentException("occurredFrom must not be after occurredTo");
        }
    }
}
