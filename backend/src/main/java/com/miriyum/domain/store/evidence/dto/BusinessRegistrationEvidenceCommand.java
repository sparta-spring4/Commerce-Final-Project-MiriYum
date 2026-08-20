package com.miriyum.domain.store.evidence.dto;

import java.util.UUID;

/** #277이 Store 공개 Service에 전달하는, 저장 완료된 비공개 증빙 참조다. */
public record BusinessRegistrationEvidenceCommand(
        long onboardingApplicationId,
        long applicationVersion,
        long storeOperatorAccountId,
        UUID fileId
) {

    public BusinessRegistrationEvidenceCommand {
        if (onboardingApplicationId <= 0 || applicationVersion <= 0 || storeOperatorAccountId <= 0 || fileId == null) {
            throw new IllegalArgumentException("application, version, operator, and file are required");
        }
    }
}
