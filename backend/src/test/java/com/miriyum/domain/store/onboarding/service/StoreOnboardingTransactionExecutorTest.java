package com.miriyum.domain.store.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.evidence.StoreBusinessRegistrationEvidenceService;
import com.miriyum.domain.store.onboarding.config.StoreOnboardingProperties;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplicationVersion;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationVersionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingAutomaticCheckJobRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class StoreOnboardingTransactionExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final long APPLICATION_ID = 41L;
    private static final String SUBMISSION_KEY = "submission-key";
    private static final String SUBMISSION_FINGERPRINT = "a".repeat(64);

    @Mock StoreOnboardingApplicationRepository applications;
    @Mock StoreOnboardingApplicationVersionRepository versions;
    @Mock StoreOnboardingAutomaticCheckJobRepository jobs;
    @Mock ObjectProvider<FileStorageFacade> storage;
    @Mock StoreBusinessRegistrationEvidenceService evidence;

    private StoreOnboardingTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new StoreOnboardingTransactionExecutor(
                applications, versions, jobs, storage, evidence,
                new StoreOnboardingProperties(false, 5000, 30000, 25, 30, 3650),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void initialSubmissionReplayKeepsApplicationVersionOneAfterSupplement() {
        StoreOnboardingApplication application = application();
        moveToChangesRequested(application, 1L);
        application.reserveSupplement(1L, "supplement-1", "b".repeat(64), false, NOW);
        given(applications.findByStoreOperatorAccountIdAndSubmissionIdempotencyKey(
                11L, SUBMISSION_KEY)).willReturn(Optional.of(application));

        var replay = executor.reserve(11L, SUBMISSION_KEY, SUBMISSION_FINGERPRINT);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.applicationVersion()).isEqualTo(1L);
    }

    @Test
    void historicalSupplementReplayDoesNotAdvanceCurrentApplication() {
        StoreOnboardingApplication application = application();
        moveToChangesRequested(application, 1L);
        application.reserveSupplement(1L, "supplement-1", "b".repeat(64), false, NOW);
        moveToChangesRequested(application, 2L);
        application.reserveSupplement(2L, "supplement-2", "c".repeat(64), false, NOW);
        moveToChangesRequested(application, 3L);
        StoreOnboardingApplicationVersion historical = org.mockito.Mockito.mock(
                StoreOnboardingApplicationVersion.class);
        given(historical.getApplicationVersion()).willReturn(2L);
        given(historical.getRequestFingerprint()).willReturn("b".repeat(64));
        given(applications.findByIdForUpdate(APPLICATION_ID)).willReturn(Optional.of(application));
        given(versions.findByStoreOnboardingApplicationIdAndSupplementIdempotencyKey(
                APPLICATION_ID, "supplement-1")).willReturn(Optional.of(historical));

        var replay = executor.reserveSupplement(
                11L, APPLICATION_ID, "supplement-1", "b".repeat(64));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.applicationVersion()).isEqualTo(2L);
        assertThat(application.getCurrentVersion()).isEqualTo(3L);
    }

    @Test
    void replayOutcomeKeepsOriginalAcceptedVersionAndState() {
        StoreOnboardingApplication application = application();
        moveToChangesRequested(application, 1L);
        application.reserveSupplement(1L, "supplement-1", "b".repeat(64), false, NOW);
        moveToChangesRequested(application, 2L);
        StoreOnboardingApplicationVersion initial = org.mockito.Mockito.mock(
                StoreOnboardingApplicationVersion.class);
        given(initial.isReviewRequired()).willReturn(false);
        given(versions.findByStoreOnboardingApplicationIdAndApplicationVersion(
                APPLICATION_ID, 1L)).willReturn(Optional.of(initial));

        var replay = executor.replayOutcome(APPLICATION_ID, 1L);

        assertThat(replay.data().get("applicationVersion").asLong()).isEqualTo(1L);
        assertThat(replay.data().get("status").asText()).isEqualTo("AUTO_CHECKING");
        assertThat(replay.data().get("nextAction").asText()).isEqualTo("WAIT");
        assertThat(replay.data().get("storeId").isNull()).isTrue();
    }

    @Test
    void historicalSupplementKeyWithDifferentFingerprintIsRejectedWithoutMutation() {
        StoreOnboardingApplication application = application();
        moveToChangesRequested(application, 1L);
        application.reserveSupplement(1L, "supplement-1", "b".repeat(64), false, NOW);
        moveToChangesRequested(application, 2L);
        application.reserveSupplement(2L, "supplement-2", "c".repeat(64), false, NOW);
        moveToChangesRequested(application, 3L);
        StoreOnboardingApplicationVersion historical = org.mockito.Mockito.mock(
                StoreOnboardingApplicationVersion.class);
        given(historical.getRequestFingerprint()).willReturn("b".repeat(64));
        given(applications.findByIdForUpdate(APPLICATION_ID)).willReturn(Optional.of(application));
        given(versions.findByStoreOnboardingApplicationIdAndSupplementIdempotencyKey(
                APPLICATION_ID, "supplement-1")).willReturn(Optional.of(historical));

        assertThatThrownBy(() -> executor.reserveSupplement(
                11L, APPLICATION_ID, "supplement-1", "d".repeat(64)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(application.getCurrentVersion()).isEqualTo(3L);
    }

    @Test
    void supplementSnapshotsTheCurrentManualReviewSwitch() {
        StoreOnboardingApplication application = application();
        moveToChangesRequested(application, 1L);
        given(applications.findByIdForUpdate(APPLICATION_ID)).willReturn(Optional.of(application));
        StoreOnboardingTransactionExecutor manualReviewExecutor = new StoreOnboardingTransactionExecutor(
                applications, versions, jobs, storage, evidence,
                new StoreOnboardingProperties(true, 5000, 30000, 25, 30, 3650),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

        var reserved = manualReviewExecutor.reserveSupplement(
                11L, APPLICATION_ID, "supplement-1", "b".repeat(64));

        assertThat(reserved.reviewRequired()).isTrue();
        assertThat(application.isReviewRequired()).isTrue();
    }

    private static StoreOnboardingApplication application() {
        StoreOnboardingApplication application = StoreOnboardingApplication.reserve(
                11L, SUBMISSION_KEY, SUBMISSION_FINGERPRINT, false, NOW);
        ReflectionTestUtils.setField(application, "id", APPLICATION_ID);
        return application;
    }

    private static void moveToChangesRequested(
            StoreOnboardingApplication application, long version) {
        if (application.getStatus()
                == com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus.RECEIVED) {
            application.beginEvidenceUpload(version);
        }
        application.attachVersion(version, NOW);
        application.requestChanges(version, NOW);
    }
}
