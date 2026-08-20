package com.miriyum.domain.store.evidence.dto;

import com.miriyum.domain.store.evidence.entity.BusinessRegistrationEvidence;
import com.miriyum.domain.store.evidence.enums.BusinessRegistrationEvidenceStatus;
import java.time.Instant;
import java.util.UUID;

/** 심사 workflow에 공개하는 URL 없는 사업자등록증 증빙 projection이다. */
public record BusinessRegistrationEvidenceView(
        UUID evidenceId,
        long applicationVersion,
        BusinessRegistrationEvidenceStatus status,
        boolean current,
        Instant retentionDueAt
) {

    public static BusinessRegistrationEvidenceView from(BusinessRegistrationEvidence evidence) {
        return new BusinessRegistrationEvidenceView(
                UUID.fromString(evidence.getEvidenceId()),
                evidence.getApplicationVersion(),
                evidence.getEvidenceStatus(),
                evidence.isCurrentEvidence(),
                evidence.getRetentionDueAt());
    }
}
