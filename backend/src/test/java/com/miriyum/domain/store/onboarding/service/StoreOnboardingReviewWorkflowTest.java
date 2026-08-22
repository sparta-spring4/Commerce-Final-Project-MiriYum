package com.miriyum.domain.store.onboarding.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.store.evidence.StoreBusinessRegistrationEvidenceService;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.EvidenceReadQuery;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseType;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingReviewCase;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationVersionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingDecisionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingReviewCaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreOnboardingReviewWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final long APPLICATION_ID = 41L;
    private static final UUID EVIDENCE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440277");

    @Mock StoreOnboardingReviewCaseRepository cases;
    @Mock StoreOnboardingApplicationRepository applications;
    @Mock StoreOnboardingApplicationVersionRepository versions;
    @Mock StoreOnboardingDecisionRepository decisions;
    @Mock StoreOnboardingFinalizationService finalizer;
    @Mock StoreBusinessRegistrationEvidenceService evidence;

    private DefaultStoreOnboardingReviewWorkflow workflow;
    private StoreOnboardingApplication application;
    private StoreOnboardingReviewCase reviewCase;

    @BeforeEach
    void setUp() {
        workflow = new DefaultStoreOnboardingReviewWorkflow(
                cases, applications, versions, decisions, finalizer, evidence,
                Clock.fixed(NOW, ZoneOffset.UTC));
        application = StoreOnboardingApplication.reserve(
                11L, "submission-key", "f".repeat(64), true, NOW);
        ReflectionTestUtils.setField(application, "id", APPLICATION_ID);
        application.beginEvidenceUpload(1L);
        application.attachVersion(1L, NOW);
        application.markReviewReady(1L, NOW);
        application.beginReview(1L, NOW);
        reviewCase = StoreOnboardingReviewCase.open(
                APPLICATION_ID, 1L, ReviewCaseType.ONBOARDING, NOW);
        reviewCase.assign(1L, 91L, NOW);
        given(cases.findByCasePublicIdForUpdate(reviewCase.getCasePublicId()))
                .willReturn(Optional.of(reviewCase));
        given(applications.findByIdForUpdate(APPLICATION_ID)).willReturn(Optional.of(application));
    }

    @Test
    void terminatedCaseCannotResolveHistoricalEvidence() {
        terminateForChanges();

        assertThatThrownBy(() -> workflow.resolveEvidenceAccess(
                reviewCase.getCasePublicId(), reviewCase.getCaseVersion()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active review");
        then(evidence).shouldHaveNoInteractions();
    }

    @Test
    void terminatedCaseCannotUsePreviouslyIssuedDescriptorToReadHistoricalEvidence() {
        terminateForChanges();

        assertThatThrownBy(() -> workflow.readEvidence(new EvidenceReadQuery(
                reviewCase.getCasePublicId(), reviewCase.getCaseVersion(),
                APPLICATION_ID, 1L, EVIDENCE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active review");
        then(evidence).shouldHaveNoInteractions();
    }

    private void terminateForChanges() {
        reviewCase.requestChanges(2L, NOW);
        application.requestChanges(1L, NOW);
    }
}
