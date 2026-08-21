package com.miriyum.domain.store.dto.storeoperator;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus;

public record StoreOnboardingApplicationResponse(
        String applicationId,
        long applicationVersion,
        ApplicationStatus status,
        boolean reviewRequired,
        String nextAction,
        String storeId
) {
}
