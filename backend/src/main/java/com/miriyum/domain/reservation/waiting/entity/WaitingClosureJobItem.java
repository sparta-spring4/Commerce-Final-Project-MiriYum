package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** 종결 작업 시작 시 고정된 개별 활성 팀 처리 항목이다. */
@Entity
@Table(
        name = "waiting_closure_job_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_waiting_closure_job_items_job_team",
                columnNames = {"waiting_closure_job_id", "waiting_team_id"}
        ))
public class WaitingClosureJobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_closure_job_item_id")
    private Long id;

    @Column(name = "waiting_closure_job_id", nullable = false)
    private Long waitingClosureJobId;

    @Column(name = "waiting_team_id", nullable = false)
    private Long waitingTeamId;

    @Column(name = "expected_version", nullable = false)
    private long expectedVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WaitingClosureItemStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "claim_token", nullable = false)
    private long claimToken;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WaitingClosureJobItem() {
    }

    public static WaitingClosureJobItem pending(long jobId, long teamId, long version, Instant now) {
        WaitingClosureJobItem item = new WaitingClosureJobItem();
        item.waitingClosureJobId = jobId;
        item.waitingTeamId = teamId;
        item.expectedVersion = version;
        item.status = WaitingClosureItemStatus.PENDING;
        item.createdAt = now;
        return item;
    }

    public void claim(String owner, Instant now, Instant until) {
        if (owner == null || owner.isBlank() || owner.length() > 64 || !until.isAfter(now))
            throw new IllegalArgumentException("lease fields must be valid");
        status = WaitingClosureItemStatus.PROCESSING; attemptCount++; lastAttemptedAt = now;
        leaseOwner = owner; leaseUntil = until; claimToken++;
    }
    public boolean isOwnedBy(String owner, long token, Instant now) {
        return status == WaitingClosureItemStatus.PROCESSING
                && claimToken == token
                && owner.equals(leaseOwner)
                && leaseUntil != null
                && leaseUntil.isAfter(now);
    }
    public boolean isExpiredAt(Instant now) { return leaseUntil != null && !leaseUntil.isAfter(now); }
    public void complete(String owner, long token, Instant now) {
        requireFence(owner, token, now); status = WaitingClosureItemStatus.COMPLETED; completedAt = now; clearLease();
    }
    public void requeue(String owner, long token, Instant now) {
        requireFence(owner, token, now); status = WaitingClosureItemStatus.PENDING; clearLease();
    }
    public void requireReconciliation(String owner, long token, Instant now) {
        requireFence(owner, token, now); status = WaitingClosureItemStatus.RECONCILIATION_REQUIRED; completedAt = now; clearLease();
    }
    public void reconcileExpired(Instant now) {
        if (status != WaitingClosureItemStatus.PROCESSING || !isExpiredAt(now)) throw new IllegalStateException("lease is not expired");
        status = WaitingClosureItemStatus.RECONCILIATION_REQUIRED; completedAt = now; clearLease();
    }
    private void requireFence(String owner, long token, Instant now) {
        if (!isOwnedBy(owner, token, now)) throw new IllegalStateException("stale closure claim");
    }
    private void clearLease() { leaseOwner = null; leaseUntil = null; }
    public Long getId() { return id; }
    public Long getWaitingClosureJobId() { return waitingClosureJobId; }
    public Long getWaitingTeamId() { return waitingTeamId; }
    public long getExpectedVersion() { return expectedVersion; }
    public WaitingClosureItemStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public long getClaimToken() { return claimToken; }
}
