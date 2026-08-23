package com.miriyum.domain.store.onboarding.service;

import java.time.LocalDate;

public interface BusinessRegistrationVerificationPort {
    VerificationResult verify(VerificationRequest request);

    record VerificationRequest(
            String businessNumber,
            String legalName,
            String representativeName,
            LocalDate openingDate,
            String primaryCategory,
            String primaryItem,
            String policyVersion
    ) {
    }

    record VerificationResult(Outcome outcome, String reasonCode, String providerVersion) {
        public VerificationResult {
            if (outcome == null || reasonCode == null || reasonCode.isBlank()
                    || providerVersion == null || providerVersion.isBlank()) {
                throw new IllegalArgumentException("verification result fields are required");
            }
        }
    }

    enum Outcome { PASSED, REJECTED, RETRYABLE_FAILURE }
}
