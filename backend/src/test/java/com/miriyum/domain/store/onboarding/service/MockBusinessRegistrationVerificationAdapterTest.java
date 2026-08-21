package com.miriyum.domain.store.onboarding.service;

import static com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome.PASSED;
import static com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.VerificationRequest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MockBusinessRegistrationVerificationAdapterTest {

    private final MockBusinessRegistrationVerificationAdapter adapter =
            new MockBusinessRegistrationVerificationAdapter();

    @Test
    void passesOnlyCompleteWellFormedRegistrationData() {
        assertThat(adapter.verify(new VerificationRequest(
                "1234567890", "미리윰 주식회사", "김대표", LocalDate.of(2020, 1, 1),
                "음식점업", "카페", "BUSINESS_REGISTRATION_AUTO_V1")).outcome())
                .isEqualTo(PASSED);
        assertThat(adapter.verify(new VerificationRequest(
                "123", "미리윰 주식회사", "김대표", LocalDate.of(2020, 1, 1),
                "음식점업", "카페", "BUSINESS_REGISTRATION_AUTO_V1")).outcome())
                .isEqualTo(REJECTED);
    }
}
