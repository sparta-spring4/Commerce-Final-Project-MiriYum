package com.miriyum.domain.store.onboarding.entity;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.AutomaticCheckStatus;
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
import com.miriyum.domain.store.onboarding.service.BusinessRegistrationVerificationPort.Outcome;

@Entity
@Table(name = "store_onboarding_automatic_check_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOnboardingAutomaticCheckJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_onboarding_automatic_check_job_id")
    private Long id;

    @Column(name = "store_onboarding_application_id", nullable = false)
    private long storeOnboardingApplicationId;

    @Column(name = "application_version", nullable = false)
    private long applicationVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AutomaticCheckStatus status;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_token", nullable = false)
    private long leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "result_code", length = 50)
    private String resultCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    public static StoreOnboardingAutomaticCheckJob pending(
            long applicationId,
            long applicationVersion,
            Instant now,
            Instant nextAttemptAt
    ) {
        if (applicationId <= 0 || applicationVersion <= 0 || now == null || nextAttemptAt == null) {
            throw new IllegalArgumentException("automatic check job fields are required");
        }
        StoreOnboardingAutomaticCheckJob job = new StoreOnboardingAutomaticCheckJob();
        job.storeOnboardingApplicationId = applicationId;
        job.applicationVersion = applicationVersion;
        job.status = AutomaticCheckStatus.PENDING;
        job.leaseToken = 0L;
        job.attemptCount = 0;
        job.nextAttemptAt = nextAttemptAt;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public long claim(String owner, Instant now, Instant expiresAt) {
        boolean pendingDue = status == AutomaticCheckStatus.PENDING
                && !nextAttemptAt.isAfter(now);
        boolean expired = status == AutomaticCheckStatus.PROCESSING
                && leaseExpiresAt != null && !leaseExpiresAt.isAfter(now);
        if ((!pendingDue && !expired) || owner == null || owner.isBlank()
                || expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("automatic check job is not claimable");
        }
        status = AutomaticCheckStatus.PROCESSING;
        leaseOwner = owner;
        leaseToken = Math.addExact(leaseToken, 1L);
        leaseExpiresAt = expiresAt;
        updatedAt = now;
        return leaseToken;
    }

    public AutomaticCheckStatus record(
            String owner,
            long token,
            Outcome outcome,
            String code,
            Instant now,
            Instant retryAt
    ) {
        if (status != AutomaticCheckStatus.PROCESSING
                || !java.util.Objects.equals(leaseOwner, owner) || leaseToken != token) {
            throw new IllegalStateException("stale automatic check claim");
        }
        resultCode = code;
        updatedAt = now;
        if (outcome == Outcome.PASSED) {
            status = AutomaticCheckStatus.PASSED;
        } else if (outcome == Outcome.REJECTED) {
            status = AutomaticCheckStatus.REJECTED;
        } else {
            attemptCount = Math.addExact(attemptCount, 1);
            if (attemptCount >= 5) {
                status = AutomaticCheckStatus.EXHAUSTED;
            } else {
                status = AutomaticCheckStatus.PENDING;
                nextAttemptAt = java.util.Objects.requireNonNull(retryAt, "retry time is required");
            }
        }
        leaseOwner = null;
        leaseExpiresAt = null;
        return status;
    }
}
