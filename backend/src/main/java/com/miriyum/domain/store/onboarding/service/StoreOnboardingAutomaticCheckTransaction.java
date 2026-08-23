package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.onboarding.config.StoreOnboardingProperties;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplicationVersion;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingAutomaticCheckJob;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.AutomaticCheckStatus;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseType;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingReviewCase;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationVersionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingAutomaticCheckJobRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingReviewCaseRepository;
import com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.VerificationRequest;
import com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.VerificationResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreOnboardingAutomaticCheckTransaction {

    private static final List<Duration> BACKOFFS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12));

    private final StoreOnboardingAutomaticCheckJobRepository jobs;
    private final StoreOnboardingApplicationVersionRepository versions;
    private final StoreOnboardingApplicationRepository applications;
    private final StoreOnboardingReviewCaseRepository reviewCases;
    private final StoreOnboardingProperties properties;
    private final Clock clock;

    @Transactional
    public Optional<Claim> claim(String owner) {
        Instant now = clock.instant();
        return jobs.findClaimableForUpdate(now, PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(job -> new Claim(
                        job.getId(), job.getStoreOnboardingApplicationId(),
                        job.getApplicationVersion(),
                        job.claim(owner, now, now.plusSeconds(properties.automaticCheckLeaseSeconds())),
                        owner));
    }

    @Transactional(readOnly = true)
    public VerificationRequest request(Claim claim) {
        StoreOnboardingApplicationVersion version = versions
                .findByStoreOnboardingApplicationIdAndApplicationVersion(
                        claim.applicationId(), claim.applicationVersion())
                .orElseThrow(() -> new IllegalStateException("onboarding version is missing"));
        return new VerificationRequest(
                version.getBusinessRegistrationNumber(), version.getLegalBusinessName(),
                version.getRepresentativeName(), version.getOpeningDate(),
                version.getPrimaryBusinessCategory(), version.getPrimaryBusinessItem(),
                version.getAutomaticCheckPolicyVersion());
    }

    @Transactional
    public RecordOutcome record(Claim claim, VerificationResult result) {
        Instant now = clock.instant();
        StoreOnboardingAutomaticCheckJob job = jobs.findByIdForUpdate(claim.jobId())
                .orElseThrow(() -> new IllegalStateException("automatic check job is missing"));
        Instant retryAt = now.plus(BACKOFFS.get(Math.min(job.getAttemptCount(), BACKOFFS.size() - 1)));
        AutomaticCheckStatus status = job.record(
                claim.owner(), claim.leaseToken(), result.outcome(), result.reasonCode(), now, retryAt);
        StoreOnboardingApplication application = applications.findByIdForUpdate(claim.applicationId())
                .orElseThrow(() -> new IllegalStateException("onboarding application is missing"));
        if (status == AutomaticCheckStatus.PASSED && application.isReviewRequired()) {
            application.markReviewReady(claim.applicationVersion(), now);
            reviewCases.save(StoreOnboardingReviewCase.open(
                    claim.applicationId(), claim.applicationVersion(), ReviewCaseType.ONBOARDING, now));
            return RecordOutcome.REVIEW_READY;
        }
        if (status == AutomaticCheckStatus.PASSED) return RecordOutcome.AUTO_FINALIZE;
        if (status == AutomaticCheckStatus.REJECTED) {
            application.rejectAutomatically(claim.applicationVersion(), now);
            return RecordOutcome.REJECTED;
        }
        return status == AutomaticCheckStatus.EXHAUSTED
                ? RecordOutcome.EXHAUSTED : RecordOutcome.RETRY_SCHEDULED;
    }

    public record Claim(
            long jobId,
            long applicationId,
            long applicationVersion,
            long leaseToken,
            String owner
    ) {
        public Claim(long jobId, long applicationId, long applicationVersion, long leaseToken) {
            this(jobId, applicationId, applicationVersion, leaseToken, "worker-a");
        }
    }

    public enum RecordOutcome {
        AUTO_FINALIZE, REVIEW_READY, REJECTED, RETRY_SCHEDULED, EXHAUSTED
    }
}
