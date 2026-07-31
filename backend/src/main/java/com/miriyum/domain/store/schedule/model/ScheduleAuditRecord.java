package com.miriyum.domain.store.schedule.model;

import java.time.Instant;

public record ScheduleAuditRecord(
        ScheduleStream stream,
        long targetVersion,
        Long previousActiveVersion,
        Long newActiveVersion,
        ScheduleAuditAction action,
        ScheduleVersionStatus previousStatus,
        ScheduleVersionStatus newStatus,
        String timeZoneId,
        Instant requestedAt,
        Instant effectiveAt,
        Instant occurredAt,
        String changeReason,
        String requestId,
        ScheduleAuditOutcome outcome
) {
}
