package com.miriyum.domain.store.onboarding.entity;

import static com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseStatus.APPROVED;
import static com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseType.ONBOARDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StoreOnboardingReviewCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void firstTerminalDecisionWins() {
        StoreOnboardingReviewCase reviewCase =
                StoreOnboardingReviewCase.open(41L, 1L, ONBOARDING, NOW);
        reviewCase.assign(1L, 91L, NOW);
        reviewCase.approve(2L, NOW.plusSeconds(1));

        assertThat(reviewCase.getStatus()).isEqualTo(APPROVED);
        assertThatThrownBy(() -> reviewCase.reject(3L, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }
}
