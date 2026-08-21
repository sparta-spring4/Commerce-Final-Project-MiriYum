package com.miriyum.domain.store.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BusinessRegistrationVerificationAdapterContextTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(
                    MockBusinessRegistrationVerificationAdapter.class,
                    UnavailableBusinessRegistrationVerificationAdapter.class);

    @Test
    void defaultVerifierUsesMock() {
        context.run(result -> {
            assertThat(result).hasSingleBean(BusinessRegistrationVerificationPort.class);
            assertThat(result).hasSingleBean(MockBusinessRegistrationVerificationAdapter.class);
            assertThat(result.getBean(BusinessRegistrationVerificationPort.class))
                    .isInstanceOf(MockBusinessRegistrationVerificationAdapter.class);
            assertThat(result)
                    .doesNotHaveBean(UnavailableBusinessRegistrationVerificationAdapter.class);
        });
    }

    @Test
    void explicitFalseDisablesMockAndFailsClosed() {
        context.withPropertyValues("miriyum.store.onboarding.dev-stub-enabled=false")
                .run(result -> {
                    assertThat(result).hasSingleBean(BusinessRegistrationVerificationPort.class);
                    assertThat(result).doesNotHaveBean(MockBusinessRegistrationVerificationAdapter.class);
                    assertThat(result.getBean(BusinessRegistrationVerificationPort.class))
                            .isInstanceOf(UnavailableBusinessRegistrationVerificationAdapter.class);
                    assertThat(result.getBean(BusinessRegistrationVerificationPort.class)
                            .verify(null).outcome())
                            .isEqualTo(BusinessRegistrationVerificationPort.Outcome.RETRYABLE_FAILURE);
                });
    }
}
