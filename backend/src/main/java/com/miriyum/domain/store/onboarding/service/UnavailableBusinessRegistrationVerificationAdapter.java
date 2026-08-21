package com.miriyum.domain.store.onboarding.service;

import static com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome.RETRYABLE_FAILURE;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fail-closed placeholder used until an external business-registration provider is configured. */
@Component
@ConditionalOnProperty(
        prefix = "miriyum.store.onboarding",
        name = "dev-stub-enabled",
        havingValue = "false")
public class UnavailableBusinessRegistrationVerificationAdapter
        implements BusinessRegistrationVerificationPort {

    @Override
    public VerificationResult verify(VerificationRequest request) {
        return new VerificationResult(
                RETRYABLE_FAILURE, "PROVIDER_UNAVAILABLE", "unavailable-v1");
    }
}
