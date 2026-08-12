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

    public void claim(Instant now) { status = WaitingClosureItemStatus.PROCESSING; attemptCount++; lastAttemptedAt = now; }
    public void complete(Instant now) { status = WaitingClosureItemStatus.COMPLETED; completedAt = now; }
    public void requeue() { status = WaitingClosureItemStatus.PENDING; }
    public void requireReconciliation(Instant now) { status = WaitingClosureItemStatus.RECONCILIATION_REQUIRED; completedAt = now; }
    public Long getId() { return id; }
    public Long getWaitingClosureJobId() { return waitingClosureJobId; }
    public Long getWaitingTeamId() { return waitingTeamId; }
    public long getExpectedVersion() { return expectedVersion; }
    public WaitingClosureItemStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
}
