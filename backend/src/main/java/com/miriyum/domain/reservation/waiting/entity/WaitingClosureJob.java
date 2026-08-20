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
import jakarta.persistence.Version;
import java.time.Instant;

/** 설정 비활성화가 고정한 활성 웨이팅 팀 종결 작업 스냅샷이다. */
@Entity
@Table(
        name = "waiting_closure_jobs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_waiting_closure_jobs_store_settings",
                columnNames = {"store_id", "settings_version"}
        ))
public class WaitingClosureJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_closure_job_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "settings_version", nullable = false)
    private long settingsVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WaitingClosureJobStatus status;

    @Column(name = "target_team_count", nullable = false)
    private long targetTeamCount;

    @Column(name = "completed_team_count", nullable = false)
    private long completedTeamCount;

    @Column(name = "failed_team_count", nullable = false)
    private long failedTeamCount;

    @Column(name = "reconciliation_required_team_count", nullable = false)
    private long reconciliationRequiredTeamCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WaitingClosureJob() {
    }

    public static WaitingClosureJob create(long storeId, long settingsVersion, long targetCount, Instant now) {
        WaitingClosureJob job = new WaitingClosureJob();
        job.storeId = storeId;
        job.settingsVersion = settingsVersion;
        job.status = targetCount == 0 ? WaitingClosureJobStatus.COMPLETED : WaitingClosureJobStatus.PENDING;
        job.targetTeamCount = targetCount;
        job.createdAt = now;
        job.completedAt = targetCount == 0 ? now : null;
        return job;
    }

    public void markProcessing() { if (status == WaitingClosureJobStatus.PENDING) status = WaitingClosureJobStatus.PROCESSING; }
    public void resumePending() { if (status == WaitingClosureJobStatus.PROCESSING) status = WaitingClosureJobStatus.PENDING; }

    public void reconcile(long completed, long failed, long reconciliation, Instant now) {
        completedTeamCount = completed;
        failedTeamCount = failed;
        reconciliationRequiredTeamCount = reconciliation;
        if (completed + failed + reconciliation == targetTeamCount) {
            status = reconciliation > 0 || failed > 0
                    ? WaitingClosureJobStatus.RECONCILIATION_REQUIRED : WaitingClosureJobStatus.COMPLETED;
            completedAt = now;
        }
    }

    public Long getId() { return id; }
    public Long getStoreId() { return storeId; }
    public long getSettingsVersion() { return settingsVersion; }
    public WaitingClosureJobStatus getStatus() { return status; }
    public long getTargetTeamCount() { return targetTeamCount; }
    public long getCompletedTeamCount() { return completedTeamCount; }
    public long getFailedTeamCount() { return failedTeamCount; }
    public long getReconciliationRequiredTeamCount() { return reconciliationRequiredTeamCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
