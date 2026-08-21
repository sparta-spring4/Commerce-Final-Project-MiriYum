package com.miriyum.domain.store.onboarding.entity;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseStatus;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseType;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_onboarding_review_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOnboardingReviewCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_onboarding_review_case_id")
    private Long id;

    @Column(name = "case_public_id", nullable = false, length = 36, unique = true)
    private String casePublicId;

    @Column(name = "store_onboarding_application_id", nullable = false)
    private long storeOnboardingApplicationId;

    @Column(name = "application_version", nullable = false)
    private long applicationVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 30)
    private ReviewCaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReviewCaseStatus status;

    @Column(name = "case_version", nullable = false)
    private long caseVersion;

    @Column(name = "active_marker")
    private Integer activeMarker;

    @Column(name = "assigned_platform_operator_id")
    private Long assignedPlatformOperatorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    public static StoreOnboardingReviewCase open(
            long applicationId,
            long applicationVersion,
            ReviewCaseType type,
            Instant now
    ) {
        requirePositive(applicationId, "application id");
        requirePositive(applicationVersion, "application version");
        StoreOnboardingReviewCase reviewCase = new StoreOnboardingReviewCase();
        reviewCase.casePublicId = UUID.randomUUID().toString();
        reviewCase.storeOnboardingApplicationId = applicationId;
        reviewCase.applicationVersion = applicationVersion;
        reviewCase.caseType = java.util.Objects.requireNonNull(type, "case type is required");
        reviewCase.status = ReviewCaseStatus.REVIEW_READY;
        reviewCase.caseVersion = 1L;
        reviewCase.activeMarker = 1;
        reviewCase.createdAt = requireTime(now);
        reviewCase.updatedAt = now;
        return reviewCase;
    }

    public void assign(long expectedCaseVersion, long platformOperatorId, Instant now) {
        requireCaseVersion(expectedCaseVersion);
        requirePositive(platformOperatorId, "platform operator id");
        if (status != ReviewCaseStatus.REVIEW_READY && status != ReviewCaseStatus.UNDER_REVIEW) {
            throw new IllegalStateException("case cannot be assigned from " + status);
        }
        assignedPlatformOperatorId = platformOperatorId;
        status = ReviewCaseStatus.UNDER_REVIEW;
        advance(now);
    }

    public void approve(long expectedCaseVersion, Instant now) {
        terminate(expectedCaseVersion, ReviewCaseStatus.APPROVED, now);
    }

    public void reject(long expectedCaseVersion, Instant now) {
        terminate(expectedCaseVersion, ReviewCaseStatus.REJECTED, now);
    }

    public void requestChanges(long expectedCaseVersion, Instant now) {
        terminate(expectedCaseVersion, ReviewCaseStatus.CHANGES_REQUESTED, now);
    }

    private void terminate(long expectedCaseVersion, ReviewCaseStatus terminal, Instant now) {
        requireCaseVersion(expectedCaseVersion);
        if (status != ReviewCaseStatus.UNDER_REVIEW || activeMarker == null) {
            throw new IllegalStateException("only an active assigned case can be decided");
        }
        status = terminal;
        activeMarker = null;
        advance(now);
    }

    private void advance(Instant now) {
        caseVersion = Math.addExact(caseVersion, 1L);
        updatedAt = requireTime(now);
    }

    private void requireCaseVersion(long expected) {
        if (caseVersion != expected) {
            throw new IllegalStateException("stale case version");
        }
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static Instant requireTime(Instant value) {
        if (value == null) throw new IllegalArgumentException("time is required");
        return value;
    }
}
