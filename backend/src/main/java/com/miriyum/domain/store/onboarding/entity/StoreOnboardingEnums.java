package com.miriyum.domain.store.onboarding.entity;

public final class StoreOnboardingEnums {

    private StoreOnboardingEnums() {
    }

    public enum ApplicationStatus {
        RECEIVED, EVIDENCE_PENDING, AUTO_CHECKING, REVIEW_READY, UNDER_REVIEW,
        CHANGES_REQUESTED, AUTO_APPROVED, APPROVED, REJECTED
    }

    public enum AutomaticCheckStatus {
        PENDING, PROCESSING, PASSED, REJECTED, EXHAUSTED
    }

    public enum ReviewCaseType {
        ONBOARDING, OWNERSHIP_CONFLICT
    }

    public enum ReviewCaseStatus {
        REVIEW_READY, UNDER_REVIEW, CHANGES_REQUESTED, APPROVED, REJECTED, CLOSED
    }

    public enum DecisionType {
        APPROVE, REJECT, REQUEST_CHANGES
    }
}
