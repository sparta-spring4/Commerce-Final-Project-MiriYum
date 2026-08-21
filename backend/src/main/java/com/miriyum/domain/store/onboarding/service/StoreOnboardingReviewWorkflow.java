package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.evidence.StoreBusinessRegistrationEvidenceService;
import com.miriyum.domain.store.evidence.dto.BusinessRegistrationEvidenceContent;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ApplicationData;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.EvidenceAccessDescriptor;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.EvidenceReadQuery;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseDetail;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCasePage;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseQuery;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseSummary;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewDecisionCommand;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewDecisionAction;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewStatus;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewType;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingDecision;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.DecisionType;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseStatus;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseType;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingReviewCase;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationVersionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingDecisionRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingReviewCaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

public interface StoreOnboardingReviewWorkflow {
    ReviewCasePage listReviewCases(ReviewCaseQuery query);
    ReviewCaseDetail getReviewCase(String caseId);
    ReviewCaseDetail assign(String caseId, long expectedCaseVersion, long operatorId, Instant expiresAt);
    ReviewCaseDetail reassign(String caseId, long expectedCaseVersion,
            long currentOperatorId, long nextOperatorId, Instant expiresAt);
    ApplicationData decide(ReviewDecisionCommand command);
    EvidenceAccessDescriptor resolveEvidenceAccess(String caseId, long expectedCaseVersion);
    BusinessRegistrationEvidenceContent readEvidence(EvidenceReadQuery query);
}

@Service
@RequiredArgsConstructor
class DefaultStoreOnboardingReviewWorkflow implements StoreOnboardingReviewWorkflow {

    private final StoreOnboardingReviewCaseRepository cases;
    private final StoreOnboardingApplicationRepository applications;
    private final StoreOnboardingApplicationVersionRepository versions;
    private final StoreOnboardingDecisionRepository decisions;
    private final StoreOnboardingFinalizationService finalizer;
    private final StoreBusinessRegistrationEvidenceService evidence;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public ReviewCasePage listReviewCases(ReviewCaseQuery query) {
        var page = cases.search(
                query.status() == null ? null : ReviewCaseStatus.valueOf(query.status().name()),
                query.type() == null ? null : ReviewCaseType.valueOf(query.type().name()),
                PageRequest.of(query.page(), query.size()));
        var content = page.getContent().stream().map(this::summary).toList();
        return new ReviewCasePage(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewCaseDetail getReviewCase(String caseId) {
        return detail(load(caseId));
    }

    @Override
    @Transactional
    public ReviewCaseDetail assign(
            String caseId, long expectedCaseVersion, long operatorId, Instant expiresAt) {
        StoreOnboardingReviewCase reviewCase = loadForUpdate(caseId);
        reviewCase.assign(expectedCaseVersion, operatorId, clock.instant());
        applicationForUpdate(reviewCase).beginReview(reviewCase.getApplicationVersion(), clock.instant());
        return detail(reviewCase);
    }

    @Override
    @Transactional
    public ReviewCaseDetail reassign(
            String caseId, long expectedCaseVersion, long currentOperatorId,
            long nextOperatorId, Instant expiresAt) {
        StoreOnboardingReviewCase reviewCase = loadForUpdate(caseId);
        if (!java.util.Objects.equals(reviewCase.getAssignedPlatformOperatorId(), currentOperatorId)) {
            throw new IllegalStateException("current onboarding assignee does not match");
        }
        reviewCase.assign(expectedCaseVersion, nextOperatorId, clock.instant());
        return detail(reviewCase);
    }

    @Override
    @Transactional
    public ApplicationData decide(ReviewDecisionCommand command) {
        StoreOnboardingReviewCase reviewCase = loadForUpdate(command.caseId());
        if (reviewCase.getApplicationVersion() != command.expectedApplicationVersion()) {
            throw new IllegalStateException("stale onboarding application version");
        }
        StoreOnboardingApplication application = applicationForUpdate(reviewCase);
        DecisionType persistedAction = DecisionType.valueOf(command.action().name());
        decisions.saveAndFlush(StoreOnboardingDecision.record(
                reviewCase.getCasePublicId(), command.expectedCaseVersion(),
                command.context().operatorId(), persistedAction, command.reasonCode(), null,
                command.idempotencyKey(), clock.instant()));
        if (command.action() == ReviewDecisionAction.APPROVE) {
            reviewCase.approve(command.expectedCaseVersion(), clock.instant());
            finalizer.finalizeApproved(
                    application.getId(), application.getCurrentVersion(),
                    StoreOnboardingFinalizationService.ApprovalMode.MANUAL);
        } else if (command.action() == ReviewDecisionAction.REJECT) {
            reviewCase.reject(command.expectedCaseVersion(), clock.instant());
            application.rejectManually(application.getCurrentVersion(), clock.instant());
        } else {
            reviewCase.requestChanges(command.expectedCaseVersion(), clock.instant());
            application.requestChanges(application.getCurrentVersion(), clock.instant());
        }
        return applicationData(application);
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceAccessDescriptor resolveEvidenceAccess(String caseId, long expectedCaseVersion) {
        StoreOnboardingReviewCase reviewCase = load(caseId);
        if (reviewCase.getCaseVersion() != expectedCaseVersion) {
            throw new IllegalStateException("stale evidence access request");
        }
        UUID evidenceId = evidence.findCurrentEvidence(
                        reviewCase.getStoreOnboardingApplicationId(), reviewCase.getApplicationVersion())
                .orElseThrow(() -> new IllegalStateException("onboarding evidence is missing"))
                .evidenceId();
        return new EvidenceAccessDescriptor(
                reviewCase.getCasePublicId(), reviewCase.getCaseVersion(),
                reviewCase.getStoreOnboardingApplicationId(), reviewCase.getApplicationVersion(),
                evidenceId);
    }

    @Override
    @Transactional(readOnly = true)
    public BusinessRegistrationEvidenceContent readEvidence(EvidenceReadQuery query) {
        StoreOnboardingReviewCase reviewCase = load(query.caseId());
        if (reviewCase.getCaseVersion() != query.expectedCaseVersion()
                || reviewCase.getStoreOnboardingApplicationId() != query.applicationId()
                || reviewCase.getApplicationVersion() != query.applicationVersion()) {
            throw new IllegalStateException("stale evidence read request");
        }
        return evidence.readCurrentEvidence(
                query.applicationId(), query.applicationVersion(), query.evidenceId());
    }

    private ReviewCaseSummary summary(StoreOnboardingReviewCase reviewCase) {
        var version = version(reviewCase);
        return new ReviewCaseSummary(
                reviewCase.getCasePublicId(), ReviewType.valueOf(reviewCase.getCaseType().name()),
                ReviewStatus.valueOf(reviewCase.getStatus().name()),
                reviewCase.getStoreOnboardingApplicationId(), reviewCase.getApplicationVersion(),
                reviewCase.getCaseVersion(), mask(version.getBusinessRegistrationNumber()),
                reviewCase.getCreatedAt());
    }

    private ReviewCaseDetail detail(StoreOnboardingReviewCase reviewCase) {
        StoreOnboardingApplication application = applications
                .findById(reviewCase.getStoreOnboardingApplicationId())
                .orElseThrow(() -> new IllegalStateException("onboarding application is missing"));
        return new ReviewCaseDetail(
                reviewCase.getCasePublicId(), ReviewType.valueOf(reviewCase.getCaseType().name()),
                ReviewStatus.valueOf(reviewCase.getStatus().name()),
                reviewCase.getStoreOnboardingApplicationId(), reviewCase.getApplicationVersion(),
                reviewCase.getCaseVersion(), reviewCase.getAssignedPlatformOperatorId(),
                applicationData(application));
    }

    private ApplicationData applicationData(StoreOnboardingApplication application) {
        return new ApplicationData(
                Long.toString(application.getId()), application.getCurrentVersion(),
                application.getStatus(), application.isReviewRequired(), "WAIT",
                application.getResultingStoreId() == null ? null
                        : Long.toString(application.getResultingStoreId()));
    }

    private StoreOnboardingReviewCase load(String caseId) {
        return cases.findByCasePublicId(caseId)
                .orElseThrow(() -> new IllegalStateException("onboarding review case is missing"));
    }

    private StoreOnboardingReviewCase loadForUpdate(String caseId) {
        return cases.findByCasePublicIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalStateException("onboarding review case is missing"));
    }

    private StoreOnboardingApplication applicationForUpdate(StoreOnboardingReviewCase reviewCase) {
        return applications.findByIdForUpdate(reviewCase.getStoreOnboardingApplicationId())
                .orElseThrow(() -> new IllegalStateException("onboarding application is missing"));
    }

    private com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplicationVersion version(
            StoreOnboardingReviewCase reviewCase) {
        return versions.findByStoreOnboardingApplicationIdAndApplicationVersion(
                        reviewCase.getStoreOnboardingApplicationId(), reviewCase.getApplicationVersion())
                .orElseThrow(() -> new IllegalStateException("onboarding version is missing"));
    }

    private static String mask(String number) {
        return number.substring(0, 3) + "-**-*****";
    }
}
