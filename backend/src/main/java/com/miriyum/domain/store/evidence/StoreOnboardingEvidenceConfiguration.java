package com.miriyum.domain.store.evidence;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.store.onboarding.repository.StoreOnboardingApplicationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 증빙 원장이 Store onboarding aggregate의 현재 소유권만 신뢰하도록 연결한다. */
@Configuration
public class StoreOnboardingEvidenceConfiguration {

    @Bean
    StoreOnboardingApplicationOwnershipPort storeOnboardingApplicationOwnershipPort(
            StoreOnboardingApplicationRepository applications
    ) {
        return (applicationId, applicationVersion, operatorAccountId) -> applications
                .findById(applicationId)
                .filter(application -> application.getStoreOperatorAccountId() == operatorAccountId)
                .filter(application -> application.getCurrentVersion() == applicationVersion)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.VALIDATION_FAILED));
    }
}
