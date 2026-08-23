package com.miriyum.domain.store.onboarding.service;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ApplicationData;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingAutomaticCheckJobRepository;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.AutomaticCheckStatus;
import com.miriyum.global.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreOnboardingQueryService {

    private final StoreOnboardingApplicationRepository applications;
    private final StoreOnboardingAutomaticCheckJobRepository jobs;

    @Autowired
    public StoreOnboardingQueryService(
            StoreOnboardingApplicationRepository applications,
            StoreOnboardingAutomaticCheckJobRepository jobs
    ) {
        this.applications = applications;
        this.jobs = jobs;
    }

    StoreOnboardingQueryService(StoreOnboardingApplicationRepository applications) {
        this(applications, null);
    }

    @Transactional(readOnly = true)
    public ApplicationData getOwn(long operatorId, long applicationId) {
        StoreOnboardingApplication application = applications.findById(applicationId)
                .orElseThrow(() -> new ServiceException(
                        StoreErrorCode.ONBOARDING_APPLICATION_NOT_FOUND));
        if (application.getStoreOperatorAccountId() != operatorId) {
            throw new ServiceException(StoreErrorCode.ACCESS_DENIED);
        }
        return new ApplicationData(
                Long.toString(application.getId()), application.getCurrentVersion(),
                application.getStatus(), application.isReviewRequired(),
                nextAction(application), application.getResultingStoreId() == null
                        ? null : Long.toString(application.getResultingStoreId()));
    }

    private String nextAction(StoreOnboardingApplication application) {
        if (jobs != null && jobs.findByStoreOnboardingApplicationIdAndApplicationVersion(
                        application.getId(), application.getCurrentVersion())
                .filter(job -> job.getStatus() == AutomaticCheckStatus.EXHAUSTED)
                .isPresent()) {
            return "MANUAL_OPERATIONS_REVIEW";
        }
        return switch (application.getStatus()) {
            case EVIDENCE_PENDING -> "UPLOAD_EVIDENCE";
            case CHANGES_REQUESTED -> "SUBMIT_CHANGES";
            case APPROVED, AUTO_APPROVED -> "COMPLETE";
            case REJECTED -> "NONE";
            default -> "WAIT";
        };
    }
}
