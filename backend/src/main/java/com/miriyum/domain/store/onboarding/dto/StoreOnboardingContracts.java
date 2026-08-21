package com.miriyum.domain.store.onboarding.dto;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StoreOnboardingContracts {
    private StoreOnboardingContracts() {
    }

    public enum ReviewDecisionAction {
        APPROVE, REJECT, REQUEST_CHANGES
    }

    public enum ReviewStatus {
        REVIEW_READY, UNDER_REVIEW, CHANGES_REQUESTED, APPROVED, REJECTED, CLOSED
    }

    public enum ReviewType {
        ONBOARDING, OWNERSHIP_CONFLICT
    }

    public record ReviewActorContext(long operatorId) {
    }

    public record ReservedApplication(
            long applicationId,
            long applicationVersion,
            boolean reviewRequired,
            boolean replayed
    ) {
    }

    public record ApplicationData(
            String applicationId,
            long applicationVersion,
            ApplicationStatus status,
            boolean reviewRequired,
            String nextAction,
            String storeId
    ) {
    }

    public record ReviewDecisionCommand(
            String caseId, long expectedCaseVersion, long expectedApplicationVersion,
            ReviewDecisionAction action, String reasonCode, ReviewActorContext context,
            String idempotencyKey) {
    }

    public record EvidenceAccessDescriptor(
            String caseId, long caseVersion, long applicationId,
            long applicationVersion, UUID evidenceId) {
    }

    public record EvidenceReadQuery(
            String caseId, long expectedCaseVersion, long applicationId,
            long applicationVersion, UUID evidenceId) {
    }

    public record ReviewCaseQuery(
            ReviewStatus status, ReviewType type, int page, int size) {
    }

    public record ReviewCaseSummary(
            String caseId, ReviewType type, ReviewStatus status,
            long applicationId, long applicationVersion, long caseVersion,
            String maskedBusinessNumber, Instant receivedAt) {
    }

    public record ReviewCasePage(
            List<ReviewCaseSummary> content, int page, int size,
            long totalElements, int totalPages) {
        public ReviewCasePage { content = List.copyOf(content); }
    }

    public record ReviewCaseDetail(
            String caseId, ReviewType type, ReviewStatus status,
            long applicationId, long applicationVersion, long caseVersion,
            Long assignedOperatorId, ApplicationData application) {
    }
}
