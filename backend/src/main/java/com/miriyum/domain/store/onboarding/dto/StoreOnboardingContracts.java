package com.miriyum.domain.store.onboarding.dto;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus;

public final class StoreOnboardingContracts {
    private StoreOnboardingContracts() {
    }

    public record ReservedApplication(
            long applicationId,
            long applicationVersion,
            boolean reviewRequired,
            boolean replayed
    ) {
    }

    public record ApplicationData(
            String applicationId,
            long applicationVersion,
            ApplicationStatus status,
            boolean reviewRequired,
            String nextAction,
            String storeId
    ) {
    }
}
