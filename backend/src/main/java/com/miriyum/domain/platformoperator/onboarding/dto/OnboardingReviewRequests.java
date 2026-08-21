package com.miriyum.domain.platformoperator.onboarding.dto;

import com.miriyum.domain.store.onboarding.dto.StoreOnboardingContracts.ReviewDecisionAction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class OnboardingReviewRequests {
    private OnboardingReviewRequests() {
    }

    public record AssignmentRequest(@Min(1) long expectedCaseVersion) {
    }

    public record ReassignmentRequest(
            @Min(1) long expectedCaseVersion,
            @Min(1) long nextOperatorId) {
    }

    public record DecisionRequest(
            @NotNull ReviewDecisionAction action,
            @Min(1) long expectedApplicationVersion,
            @Min(1) long expectedCaseVersion,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String reasonCode) {
    }
}
