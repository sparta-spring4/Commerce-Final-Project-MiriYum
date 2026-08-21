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
    void defaultVerifierFailsClosed() {
        context.run(result -> {
            assertThat(result).doesNotHaveBean(MockBusinessRegistrationVerificationAdapter.class);
            assertThat(result).hasSingleBean(BusinessRegistrationVerificationPort.class);
            assertThat(result.getBean(BusinessRegistrationVerificationPort.class))
                    .isInstanceOf(UnavailableBusinessRegistrationVerificationAdapter.class);
            assertThat(result.getBean(BusinessRegistrationVerificationPort.class)
                    .verify(null).outcome())
                    .isEqualTo(BusinessRegistrationVerificationPort.Outcome.RETRYABLE_FAILURE);
        });
    }

    @Test
    void mockVerifierRequiresExplicitDevelopmentSetting() {
        context.withPropertyValues("miriyum.store.onboarding.dev-stub-enabled=true")
                .run(result -> {
                    assertThat(result).hasSingleBean(BusinessRegistrationVerificationPort.class);
                    assertThat(result).hasSingleBean(MockBusinessRegistrationVerificationAdapter.class);
                    assertThat(result)
                            .doesNotHaveBean(UnavailableBusinessRegistrationVerificationAdapter.class);
                });
    }
}
