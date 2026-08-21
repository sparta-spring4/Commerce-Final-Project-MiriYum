package com.miriyum.domain.store.onboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "miriyum.store.onboarding")
public record StoreOnboardingProperties(
        boolean manualReviewEnabled,
        long automaticCheckDelayMs,
        long automaticCheckInitialDelayMs,
        int automaticCheckBatchSize,
        long automaticCheckLeaseSeconds,
        long caseAssignmentDays
) {
    public StoreOnboardingProperties {
        if (automaticCheckDelayMs < 0 || automaticCheckInitialDelayMs < 0
                || automaticCheckBatchSize <= 0 || automaticCheckLeaseSeconds <= 0
                || caseAssignmentDays <= 0) {
            throw new IllegalArgumentException("store onboarding runtime values are invalid");
        }
    }
}
