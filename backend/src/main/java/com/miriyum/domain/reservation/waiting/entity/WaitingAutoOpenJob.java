package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

@Entity
@Table(name = "waiting_auto_open_jobs")
public class WaitingAutoOpenJob {

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_auto_open_job_id")
    private Long id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "business_interval_key", nullable = false, length = 128, updatable = false)
    private String businessIntervalKey;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "interval_starts_at", nullable = false, updatable = false)
    private Instant intervalStartsAt;

    @Column(name = "interval_ends_at", nullable = false, updatable = false)
    private Instant intervalEndsAt;

    @Column(name = "scheduled_at", nullable = false, updatable = false)
    private Instant scheduledAt;

    @Column(name = "expected_settings_version", nullable = false, updatable = false)
    private long expectedSettingsVersion;

    @Column(name = "expected_advance_open_minutes", nullable = false, updatable = false)
    private int expectedAdvanceOpenMinutes;

    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WaitingAutoOpenJobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WaitingAutoOpenJob() {
    }

    public static WaitingAutoOpenJob pending(
            long storeId,
            String businessIntervalKey,
            LocalDate businessDate,
            Instant intervalStartsAt,
            Instant intervalEndsAt,
            Instant scheduledAt,
            long expectedSettingsVersion,
            int expectedAdvanceOpenMinutes,
            String idempotencyKey,
            Instant now
    ) {
        if (storeId <= 0 || expectedSettingsVersion <= 0
                || expectedAdvanceOpenMinutes < 0 || expectedAdvanceOpenMinutes > 180) {
            throw new IllegalArgumentException("job owner and settings snapshot are invalid");
        }
        requireText(businessIntervalKey, 128, "businessIntervalKey");
        if (idempotencyKey == null || !DIGEST.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey must be a SHA-256 digest");
        }
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        requireInterval(intervalStartsAt, intervalEndsAt);
        Objects.requireNonNull(scheduledAt, "scheduledAt must not be null");
        Objects.requireNonNull(now, "now must not be null");

        WaitingAutoOpenJob job = new WaitingAutoOpenJob();
        job.storeId = storeId;
        job.businessIntervalKey = businessIntervalKey;
        job.businessDate = businessDate;
        job.intervalStartsAt = intervalStartsAt;
        job.intervalEndsAt = intervalEndsAt;
        job.scheduledAt = scheduledAt;
        job.expectedSettingsVersion = expectedSettingsVersion;
        job.expectedAdvanceOpenMinutes = expectedAdvanceOpenMinutes;
        job.idempotencyKey = idempotencyKey;
        job.status = WaitingAutoOpenJobStatus.PENDING;
        job.nextAttemptAt = scheduledAt;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public void claim(String owner, Instant now, Instant until) {
        requireLease(owner, now, until);
        boolean due = (status == WaitingAutoOpenJobStatus.PENDING
                || status == WaitingAutoOpenJobStatus.RETRY_WAIT)
                && nextAttemptAt != null
                && !nextAttemptAt.isAfter(now);
        boolean expired = status == WaitingAutoOpenJobStatus.PROCESSING
                && leaseUntil != null
                && !leaseUntil.isAfter(now);
        if (!due && !expired) {
            throw new IllegalStateException("auto-open job is not claimable");
        }
        status = WaitingAutoOpenJobStatus.PROCESSING;
        attemptCount++;
        fencingToken++;
        leaseOwner = owner;
        leaseUntil = until;
        lastAttemptedAt = now;
        nextAttemptAt = null;
        failureCode = null;
        updatedAt = now;
    }

    public boolean isOwnedBy(String owner, long token, Instant now) {
        return owner != null
                && now != null
                && status == WaitingAutoOpenJobStatus.PROCESSING
                && fencingToken == token
                && owner.equals(leaseOwner)
                && leaseUntil != null
                && leaseUntil.isAfter(now);
    }

    public void complete(String owner, long token, Instant now) {
        requireFence(owner, token, now);
        finish(WaitingAutoOpenJobStatus.COMPLETED, null, now);
    }

    public void invalidate(String owner, long token, Instant now, String code) {
        requireFence(owner, token, now);
        finish(WaitingAutoOpenJobStatus.INVALIDATED, requireText(code, 64, "failureCode"), now);
    }

    public void retry(
            String owner,
            long token,
            Instant now,
            Duration delay,
            String code
    ) {
        requireFence(owner, token, now);
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
        status = WaitingAutoOpenJobStatus.RETRY_WAIT;
        failureCode = requireText(code, 64, "failureCode");
        nextAttemptAt = now.plus(delay);
        clearLease();
        updatedAt = now;
    }

    public void requireReconciliation(String owner, long token, Instant now, String code) {
        requireFence(owner, token, now);
        finish(
                WaitingAutoOpenJobStatus.RECONCILIATION_REQUIRED,
                requireText(code, 64, "failureCode"),
                now);
    }

    private void requireFence(String owner, long token, Instant now) {
        if (!isOwnedBy(owner, token, now)) {
            throw new IllegalStateException("stale auto-open claim");
        }
    }

    private void finish(WaitingAutoOpenJobStatus terminal, String code, Instant now) {
        status = terminal;
        failureCode = code;
        completedAt = now;
        clearLease();
        updatedAt = now;
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
    }

    private static void requireLease(String owner, Instant now, Instant until) {
        if (owner == null || owner.isBlank() || owner.length() > 128
                || now == null || until == null || !until.isAfter(now)) {
            throw new IllegalArgumentException("lease fields are invalid");
        }
    }

    private static void requireInterval(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("interval must be half-open and non-empty");
        }
    }

    private static String requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    public Long getId() { return id; }
    public Long getStoreId() { return storeId; }
    public String getBusinessIntervalKey() { return businessIntervalKey; }
    public LocalDate getBusinessDate() { return businessDate; }
    public Instant getIntervalStartsAt() { return intervalStartsAt; }
    public Instant getIntervalEndsAt() { return intervalEndsAt; }
    public Instant getScheduledAt() { return scheduledAt; }
    public long getExpectedSettingsVersion() { return expectedSettingsVersion; }
    public int getExpectedAdvanceOpenMinutes() { return expectedAdvanceOpenMinutes; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public WaitingAutoOpenJobStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public long getFencingToken() { return fencingToken; }
    public String getFailureCode() { return failureCode; }
    public Instant getLastAttemptedAt() { return lastAttemptedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
