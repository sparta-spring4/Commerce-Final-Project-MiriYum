package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadService.PendingEvidence;
import com.miriyum.domain.store.evidence.StoreBusinessRegistrationEvidenceService;
import com.miriyum.domain.store.evidence.dto.BusinessRegistrationEvidenceCommand;
import com.miriyum.domain.store.onboarding.config.StoreOnboardingProperties;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ApplicationData;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReservedApplication;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplicationVersion;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingAutomaticCheckJob;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationVersionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingAutomaticCheckJobRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.storage.service.FileStorageFacade;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class StoreOnboardingTransactionExecutor {

    private static final String RESOURCE_TYPE = "STORE_ONBOARDING_APPLICATION";
    private static final String AUTO_POLICY_VERSION = "BUSINESS_REGISTRATION_AUTO_V1";
    private static final String MANUAL_POLICY_VERSION = "PLATFORM_REVIEW_V1";

    private final StoreOnboardingApplicationRepository applicationRepository;
    private final StoreOnboardingApplicationVersionRepository versionRepository;
    private final StoreOnboardingAutomaticCheckJobRepository jobRepository;
    private final ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;
    private final StoreBusinessRegistrationEvidenceService evidenceService;
    private final StoreOnboardingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public ReservedApplication reserve(
            long operatorId,
            String idempotencyKey,
            String fingerprint
    ) {
        var existing = applicationRepository
                .findByStoreOperatorAccountIdAndSubmissionIdempotencyKey(operatorId, idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), fingerprint);
        try {
            StoreOnboardingApplication saved = applicationRepository.saveAndFlush(
                    StoreOnboardingApplication.reserve(
                            operatorId, idempotencyKey, fingerprint,
                            properties.manualReviewEnabled(), clock.instant()));
            return new ReservedApplication(
                    saved.getId(), saved.getCurrentVersion(), saved.isReviewRequired(), false);
        } catch (DataIntegrityViolationException exception) {
            StoreOnboardingApplication winner = applicationRepository
                    .findByStoreOperatorAccountIdAndSubmissionIdempotencyKey(operatorId, idempotencyKey)
                    .orElseThrow(() -> exception);
            return replay(winner, fingerprint);
        }
    }

    @Transactional
    public void beginEvidenceUpload(ReservedApplication reserved) {
        StoreOnboardingApplication application = loadForUpdate(reserved.applicationId());
        application.beginEvidenceUpload(reserved.applicationVersion());
    }

    @Transactional
    public ReservedApplication reserveSupplement(
            long operatorId,
            long applicationId,
            String idempotencyKey,
            String fingerprint
    ) {
        StoreOnboardingApplication application = loadForUpdate(applicationId);
        if (application.getStoreOperatorAccountId() != operatorId) {
            throw new ServiceException(com.miriyum.domain.store.error.StoreErrorCode.ACCESS_DENIED);
        }
        if (application.getCurrentRequestIdempotencyKey().equals(idempotencyKey)) {
            if (!application.getCurrentRequestFingerprint().equals(fingerprint)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            boolean completed = application.getStatus() != ApplicationStatus.EVIDENCE_PENDING;
            return new ReservedApplication(
                    applicationId, application.getCurrentVersion(),
                    application.isReviewRequired(), completed);
        }
        var historical = versionRepository
                .findByStoreOnboardingApplicationIdAndSupplementIdempotencyKey(
                        applicationId, idempotencyKey);
        if (historical.isPresent()) {
            StoreOnboardingApplicationVersion version = historical.get();
            if (!version.getRequestFingerprint().equals(fingerprint)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new ReservedApplication(
                    applicationId, version.getApplicationVersion(),
                    application.isReviewRequired(), true);
        }
        long version = application.reserveSupplement(
                application.getCurrentVersion(), idempotencyKey, fingerprint,
                properties.manualReviewEnabled(), clock.instant());
        return new ReservedApplication(applicationId, version, application.isReviewRequired(), false);
    }

    @Transactional
    public IdempotentOutcome attachAndSubmit(
            long operatorId,
            ReservedApplication reserved,
            PendingEvidence pending,
            String fingerprint,
            StoreCreateRequest request,
            String supplementKey,
            VerifiedStoreGeocoding geocoding
    ) {
        StoreOnboardingApplication application = loadForUpdate(reserved.applicationId());
        requireFileStorageFacade().confirmWithinCurrentTransaction(pending.fileId());
        var evidence = evidenceService.replaceCurrentEvidence(
                new BusinessRegistrationEvidenceCommand(
                        reserved.applicationId(), reserved.applicationVersion(), operatorId, pending.fileId()));
        versionRepository.save(StoreOnboardingApplicationVersion.snapshot(
                reserved.applicationId(), reserved.applicationVersion(), supplementKey, fingerprint,
                evidence.evidenceId().toString(), request, geocoding, reserved.reviewRequired(),
                AUTO_POLICY_VERSION, MANUAL_POLICY_VERSION, clock.instant(), objectMapper));
        jobRepository.save(StoreOnboardingAutomaticCheckJob.pending(
                reserved.applicationId(), reserved.applicationVersion(), clock.instant(),
                clock.instant().plusMillis(properties.automaticCheckInitialDelayMs())));
        application.attachVersion(reserved.applicationVersion(), clock.instant());
        return outcome(application, false);
    }

    @Transactional(readOnly = true)
    public IdempotentOutcome replayOutcome(long applicationId, long applicationVersion) {
        StoreOnboardingApplicationVersion version = versionRepository
                .findByStoreOnboardingApplicationIdAndApplicationVersion(
                        applicationId, applicationVersion)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.VALIDATION_FAILED));
        ApplicationData data = new ApplicationData(
                Long.toString(applicationId), applicationVersion,
                ApplicationStatus.AUTO_CHECKING, version.isReviewRequired(),
                "WAIT", null);
        return new IdempotentOutcome(
                true, 202, "SUCCESS", RESOURCE_TYPE, Long.toString(applicationId),
                objectMapper.valueToTree(data));
    }

    private ReservedApplication replay(StoreOnboardingApplication application, String fingerprint) {
        if (!application.getSubmissionFingerprint().equals(fingerprint)) {
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new ReservedApplication(
                application.getId(), 1L,
                application.isReviewRequired(),
                application.getCurrentVersion() > 1L
                        || (application.getStatus() != ApplicationStatus.RECEIVED
                        && application.getStatus() != ApplicationStatus.EVIDENCE_PENDING));
    }

    private StoreOnboardingApplication loadForUpdate(long applicationId) {
        return applicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.VALIDATION_FAILED));
    }

    private IdempotentOutcome outcome(StoreOnboardingApplication application, boolean replayed) {
        ApplicationData data = new ApplicationData(
                Long.toString(application.getId()), application.getCurrentVersion(),
                application.getStatus(), application.isReviewRequired(),
                nextAction(application), application.getResultingStoreId() == null
                        ? null : Long.toString(application.getResultingStoreId()));
        return new IdempotentOutcome(
                replayed, 202, "SUCCESS", RESOURCE_TYPE, Long.toString(application.getId()),
                objectMapper.valueToTree(data));
    }

    private static String nextAction(StoreOnboardingApplication application) {
        return switch (application.getStatus()) {
            case EVIDENCE_PENDING -> "UPLOAD_EVIDENCE";
            case CHANGES_REQUESTED -> "SUBMIT_CHANGES";
            case APPROVED, AUTO_APPROVED -> "COMPLETE";
            case REJECTED -> "NONE";
            default -> "WAIT";
        };
    }

    private FileStorageFacade requireFileStorageFacade() {
        FileStorageFacade facade = fileStorageFacadeProvider.getIfAvailable();
        if (facade == null) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return facade;
    }
}
