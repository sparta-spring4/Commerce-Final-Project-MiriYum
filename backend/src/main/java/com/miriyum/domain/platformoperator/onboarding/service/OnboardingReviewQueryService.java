package com.miriyum.domain.platformoperator.onboarding.service;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseDetail;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCasePage;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseQuery;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewStatus;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewType;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingReviewWorkflow;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class OnboardingReviewQueryService {

    private final OperatorAuthorityReader authorities;
    private final StoreOnboardingReviewWorkflow workflow;

    public ReviewCasePage list(
            PlatformOperatorPrincipal principal,
            ReviewStatus status,
            ReviewType type,
            int page,
            int size
    ) {
        requirePermission(principal);
        return workflow.listReviewCases(new ReviewCaseQuery(status, type, page, size));
    }

    public ReviewCaseDetail detail(PlatformOperatorPrincipal principal, String caseId) {
        requirePermission(principal);
        return workflow.getReviewCase(caseId);
    }

    private void requirePermission(PlatformOperatorPrincipal principal) {
        if (!authorities.requireCurrentAuthority(principal.accountId(), principal.authorityVersion())
                .permissions().contains(PlatformOperatorPermission.ONBOARDING_REVIEW)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }
}
