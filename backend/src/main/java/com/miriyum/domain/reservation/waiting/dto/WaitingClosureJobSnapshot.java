package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJobStatus;
import java.time.Instant;

public record WaitingClosureJobSnapshot(
        String jobId,
        String storeId,
        WaitingClosureJobStatus status,
        long totalTeamCount,
        long completedTeamCount,
        long failedTeamCount,
        long reconciliationRequiredTeamCount,
        Instant createdAt,
        Instant completedAt
) {
    public static WaitingClosureJobSnapshot from(WaitingClosureJob job) {
        return new WaitingClosureJobSnapshot(
                Long.toString(job.getId()), Long.toString(job.getStoreId()), job.getStatus(),
                job.getTargetTeamCount(), job.getCompletedTeamCount(), job.getFailedTeamCount(),
                job.getReconciliationRequiredTeamCount(), job.getCreatedAt(), job.getCompletedAt());
    }
}
