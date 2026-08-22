package com.miriyum.domain.store.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreOnboardingQueryServiceTest {

    @Mock StoreOnboardingApplicationRepository applications;

    @Test
    void returnsOnlyTheOwnersApplication() {
        StoreOnboardingApplication application = StoreOnboardingApplication.reserve(
                11L, "key", "f".repeat(64), true, Instant.parse("2026-08-21T00:00:00Z"));
        ReflectionTestUtils.setField(application, "id", 41L);
        given(applications.findById(41L)).willReturn(Optional.of(application));
        StoreOnboardingQueryService service = new StoreOnboardingQueryService(applications);

        assertThat(service.getOwn(11L, 41L).applicationId()).isEqualTo("41");
        assertThatThrownBy(() -> service.getOwn(12L, 41L))
                .isInstanceOf(ServiceException.class);
    }
}
