package com.miriyum.domain.store.onboarding.service;

import static com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome.PASSED;
import static com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome.REJECTED;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "miriyum.store.onboarding",
        name = "dev-stub-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MockBusinessRegistrationVerificationAdapter
        implements BusinessRegistrationVerificationPort {

    @Override
    public VerificationResult verify(VerificationRequest request) {
        boolean valid = request != null
                && request.businessNumber() != null
                && request.businessNumber().matches("^[0-9]{10}$")
                && present(request.legalName())
                && present(request.representativeName())
                && request.openingDate() != null
                && present(request.primaryCategory())
                && present(request.primaryItem())
                && present(request.policyVersion());
        return new VerificationResult(
                valid ? PASSED : REJECTED,
                valid ? "MATCHED" : "REGISTRATION_DATA_INVALID",
                "mock-v1");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
