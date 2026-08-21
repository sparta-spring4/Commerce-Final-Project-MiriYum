package com.miriyum.domain.store.onboarding.entity;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_onboarding_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOnboardingApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_onboarding_application_id")
    private Long id;

    @Column(name = "store_operator_account_id", nullable = false)
    private long storeOperatorAccountId;

    @Column(name = "submission_idempotency_key", nullable = false, length = 100)
    private String submissionIdempotencyKey;

    @Column(name = "submission_fingerprint", nullable = false, length = 64)
    private String submissionFingerprint;

    @Column(name = "current_request_idempotency_key", nullable = false, length = 100)
    private String currentRequestIdempotencyKey;

    @Column(name = "current_request_fingerprint", nullable = false, length = 64)
    private String currentRequestFingerprint;

    @Column(name = "current_version", nullable = false)
    private long currentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ApplicationStatus status;

    @Column(name = "review_required", nullable = false)
    private boolean reviewRequired;

    @Column(name = "resulting_store_id", unique = true)
    private Long resultingStoreId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    public static StoreOnboardingApplication reserve(
            long operatorId,
            String idempotencyKey,
            String fingerprint,
            boolean reviewRequired,
            Instant now
    ) {
        requirePositive(operatorId, "store operator account id");
        StoreOnboardingApplication application = new StoreOnboardingApplication();
        application.storeOperatorAccountId = operatorId;
        application.submissionIdempotencyKey = requireText(idempotencyKey, "idempotency key");
        application.submissionFingerprint = requireText(fingerprint, "submission fingerprint");
        application.currentRequestIdempotencyKey = application.submissionIdempotencyKey;
        application.currentRequestFingerprint = application.submissionFingerprint;
        application.currentVersion = 1L;
        application.status = ApplicationStatus.RECEIVED;
        application.reviewRequired = reviewRequired;
        application.createdAt = requireTime(now);
        application.updatedAt = now;
        return application;
    }

    public void beginEvidenceUpload(long expectedVersion) {
        requireCurrentVersion(expectedVersion);
        if (status == ApplicationStatus.EVIDENCE_PENDING) return;
        requireStatus(ApplicationStatus.RECEIVED);
        status = ApplicationStatus.EVIDENCE_PENDING;
    }

    public void attachVersion(long expectedVersion, Instant now) {
        requireCurrentVersion(expectedVersion);
        requireStatus(ApplicationStatus.EVIDENCE_PENDING);
        status = ApplicationStatus.AUTO_CHECKING;
        updatedAt = requireTime(now);
    }

    public void requestChanges(long expectedVersion, Instant now) {
        requireCurrentVersion(expectedVersion);
        if (status != ApplicationStatus.AUTO_CHECKING
                && status != ApplicationStatus.REVIEW_READY
                && status != ApplicationStatus.UNDER_REVIEW) {
            throw new IllegalStateException("changes cannot be requested from " + status);
        }
        status = ApplicationStatus.CHANGES_REQUESTED;
        updatedAt = requireTime(now);
    }

    public long reserveSupplement(
            long expectedVersion,
            String idempotencyKey,
            String fingerprint,
            boolean reviewRequired,
            Instant now
    ) {
        requireCurrentVersion(expectedVersion);
        requireStatus(ApplicationStatus.CHANGES_REQUESTED);
        currentRequestIdempotencyKey = requireText(idempotencyKey, "idempotency key");
        currentRequestFingerprint = requireText(fingerprint, "submission fingerprint");
        currentVersion = Math.addExact(currentVersion, 1L);
        this.reviewRequired = reviewRequired;
        status = ApplicationStatus.EVIDENCE_PENDING;
        updatedAt = requireTime(now);
        return currentVersion;
    }

    public void markReviewReady(long expectedVersion, Instant now) {
        requireCurrentVersion(expectedVersion);
        requireStatus(ApplicationStatus.AUTO_CHECKING);
        status = ApplicationStatus.REVIEW_READY;
        updatedAt = requireTime(now);
    }

    public void beginReview(long expectedVersion, Instant now) {
        requireCurrentVersion(expectedVersion);
        if (status != ApplicationStatus.REVIEW_READY && status != ApplicationStatus.UNDER_REVIEW) {
            throw new IllegalStateException("application is not review ready");
        }
        status = ApplicationStatus.UNDER_REVIEW;
        updatedAt = requireTime(now);
    }

    public void rejectManually(long expectedVersion, Instant now) {
        requireCurrentVersion(expectedVersion);
        requireStatus(ApplicationStatus.UNDER_REVIEW);
        status = ApplicationStatus.REJECTED;
        updatedAt = requireTime(now);
    }

    public void rejectAutomatically(long expectedVersion, Instant now) {
        requireCurrentVersion(expectedVersion);
        requireStatus(ApplicationStatus.AUTO_CHECKING);
        status = ApplicationStatus.REJECTED;
        updatedAt = requireTime(now);
    }

    public void completeApproval(
            long expectedVersion,
            long storeId,
            boolean automatic,
            Instant now
    ) {
        requireCurrentVersion(expectedVersion);
        ApplicationStatus expected = automatic
                ? ApplicationStatus.AUTO_CHECKING : ApplicationStatus.UNDER_REVIEW;
        requireStatus(expected);
        if (storeId <= 0 || resultingStoreId != null) {
            throw new IllegalStateException("application already has a resulting store");
        }
        resultingStoreId = storeId;
        status = automatic ? ApplicationStatus.AUTO_APPROVED : ApplicationStatus.APPROVED;
        updatedAt = requireTime(now);
    }

    private void requireCurrentVersion(long expectedVersion) {
        if (expectedVersion != currentVersion) {
            throw new IllegalStateException("stale application version");
        }
    }

    private void requireStatus(ApplicationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + status);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Instant requireTime(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("time is required");
        }
        return value;
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
