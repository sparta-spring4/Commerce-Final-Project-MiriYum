package com.miriyum.domain.store.onboarding.entity;

import static com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus.EVIDENCE_PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StoreOnboardingApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void supplementInvalidatesTheOldVersion() {
        StoreOnboardingApplication application = StoreOnboardingApplication.reserve(
                11L, "submission-key", "submission-fingerprint", true, NOW);
        application.beginEvidenceUpload(1L);
        application.attachVersion(1L, NOW);
        application.requestChanges(1L, NOW);

        long nextVersion = application.reserveSupplement(
                1L, "supplement-key", "supplement-fingerprint", true, NOW);

        assertThat(nextVersion).isEqualTo(2L);
        assertThat(application.getCurrentVersion()).isEqualTo(2L);
        assertThat(application.getStatus()).isEqualTo(EVIDENCE_PENDING);
        assertThat(application.isReviewRequired()).isTrue();
    }
}
