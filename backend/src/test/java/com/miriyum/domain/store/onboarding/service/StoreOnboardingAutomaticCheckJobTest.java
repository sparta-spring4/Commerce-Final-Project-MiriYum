package com.miriyum.domain.store.onboarding.service;

import static com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome.PASSED;
import static com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome.RETRYABLE_FAILURE;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.VerificationResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;

@ExtendWith(MockitoExtension.class)
class StoreOnboardingAutomaticCheckJobTest {

    @Mock StoreOnboardingAutomaticCheckTransaction transactions;
    @Mock BusinessRegistrationVerificationPort verifier;
    @Mock StoreOnboardingFinalizationService finalizer;

    @Test
    void passedAutomaticModeUsesTheSingleFinalizer() {
        var claim = new StoreOnboardingAutomaticCheckTransaction.Claim(1L, 41L, 1L, 7L);
        var request = org.mockito.Mockito.mock(
                BusinessRegistrationVerificationPort.VerificationRequest.class);
        var result = new VerificationResult(PASSED, "MATCHED", "mock-v1");
        given(transactions.claim("worker-a")).willReturn(Optional.of(claim));
        given(transactions.request(claim)).willReturn(request);
        given(verifier.verify(request)).willReturn(result);
        given(transactions.record(claim, result)).willReturn(
                StoreOnboardingAutomaticCheckTransaction.RecordOutcome.AUTO_FINALIZE);
        StoreOnboardingAutomaticCheckJob job =
                new StoreOnboardingAutomaticCheckJob(transactions, verifier, finalizer);

        job.runOnce("worker-a");

        then(finalizer).should().finalizeApproved(
                41L, 1L, StoreOnboardingFinalizationService.ApprovalMode.AUTO);
    }

    @Test
    void passedManualReviewModeCreatesNoStoreBeforeDecision() {
        var claim = new StoreOnboardingAutomaticCheckTransaction.Claim(1L, 41L, 1L, 7L);
        var request = org.mockito.Mockito.mock(
                BusinessRegistrationVerificationPort.VerificationRequest.class);
        var result = new VerificationResult(PASSED, "MATCHED", "mock-v1");
        given(transactions.claim("worker-a")).willReturn(Optional.of(claim));
        given(transactions.request(claim)).willReturn(request);
        given(verifier.verify(request)).willReturn(result);
        given(transactions.record(claim, result)).willReturn(
                StoreOnboardingAutomaticCheckTransaction.RecordOutcome.REVIEW_READY);
        StoreOnboardingAutomaticCheckJob job =
                new StoreOnboardingAutomaticCheckJob(transactions, verifier, finalizer);

        job.runOnce("worker-a");

        then(finalizer).should(never()).finalizeApproved(anyLong(), anyLong(), any());
    }

    @Test
    void expiredClaimCanBeRecoveredAndOldFencingTokenIsRejected() {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        var entity = com.miriyum.domain.store.onboarding.entity.StoreOnboardingAutomaticCheckJob
                .pending(41L, 1L, now, now);
        long oldToken = entity.claim("worker-a", now, now.plusSeconds(30));
        long nextToken = entity.claim("worker-b", now.plusSeconds(31), now.plusSeconds(61));

        assertThat(nextToken).isGreaterThan(oldToken);
        assertThatThrownBy(() -> entity.record(
                "worker-a", oldToken, PASSED, "MATCHED", now.plusSeconds(32), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fifthRetryableFailureExhaustsTheJob() {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        var entity = com.miriyum.domain.store.onboarding.entity.StoreOnboardingAutomaticCheckJob
                .pending(41L, 1L, now, now);

        for (int attempt = 0; attempt < 5; attempt++) {
            Instant claimedAt = now.plusSeconds(attempt * 60L);
            long token = entity.claim("worker", claimedAt, claimedAt.plusSeconds(30));
            entity.record("worker", token, RETRYABLE_FAILURE, "PROVIDER_UNAVAILABLE",
                    claimedAt, claimedAt.plusSeconds(60));
        }

        assertThat(entity.getStatus()).isEqualTo(
                com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums
                        .AutomaticCheckStatus.EXHAUSTED);
        assertThat(entity.getAttemptCount()).isEqualTo(5);
    }
}
