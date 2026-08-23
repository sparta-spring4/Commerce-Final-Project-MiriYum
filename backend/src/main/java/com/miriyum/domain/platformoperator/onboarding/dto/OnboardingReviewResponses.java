package com.miriyum.domain.platformoperator.onboarding.dto;

import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ApplicationData;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCaseDetail;
import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewCasePage;

public final class OnboardingReviewResponses {
    private OnboardingReviewResponses() {
    }

    public record CasePage(ReviewCasePage data) {
    }

    public record CaseDetail(ReviewCaseDetail data) {
    }

    public record Decision(ApplicationData application) {
    }
}
