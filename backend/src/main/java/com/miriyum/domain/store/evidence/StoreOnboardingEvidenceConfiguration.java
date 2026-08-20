package com.miriyum.domain.store.evidence;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** #277의 신청 소유권 구현 전에는 증빙 연결을 실패 폐쇄한다. */
@Configuration
public class StoreOnboardingEvidenceConfiguration {

    @Bean
    @ConditionalOnMissingBean(StoreOnboardingApplicationOwnershipPort.class)
    StoreOnboardingApplicationOwnershipPort unavailableOwnershipPort() {
        return (applicationId, applicationVersion, operatorAccountId) -> {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        };
    }
}
