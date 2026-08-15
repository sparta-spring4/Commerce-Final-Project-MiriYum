package com.miriyum.domain.platformoperator.dto.audit;

import java.util.List;

public record PlatformOperatorAuditDetailData(
        PlatformOperatorAuditEventData original,
        List<PlatformOperatorAuditEventData> corrections
) {
    public PlatformOperatorAuditDetailData {
        corrections = List.copyOf(corrections);
    }
}
