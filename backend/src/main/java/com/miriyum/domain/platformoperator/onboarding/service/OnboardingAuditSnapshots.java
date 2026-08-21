package com.miriyum.domain.platformoperator.onboarding.service;

import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseDetail;
import java.util.Map;

public final class OnboardingAuditSnapshots {
    private OnboardingAuditSnapshots() {
    }

    public static Map<String, Object> of(ReviewCaseDetail detail) {
        return Map.of(
                "caseId", detail.caseId(),
                "caseVersion", detail.caseVersion(),
                "applicationId", detail.applicationId(),
                "applicationVersion", detail.applicationVersion(),
                "status", detail.status().name());
    }
}
