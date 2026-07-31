package com.miriyum.domain.store.schedule.entity;

import com.miriyum.domain.store.schedule.model.ConflictCheckStatus;
import com.miriyum.domain.store.schedule.model.ScheduleActorType;
import com.miriyum.domain.store.schedule.model.ScheduleAuditAction;
import com.miriyum.domain.store.schedule.model.ScheduleAuditOutcome;
import com.miriyum.domain.store.schedule.model.ScheduleAuditRecord;
import com.miriyum.domain.store.schedule.model.ScheduleStream;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 일정 초안·게시·취소·자동 활성화 결과를 보존하는 append-only 감사 사건이다.
 */
@Entity
@Table(name = "store_schedule_audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreScheduleAuditEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_schedule_audit_event_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_stream", nullable = false, length = 20)
    private ScheduleStream scheduleStream;

    @Column(name = "target_version", nullable = false)
    private long targetVersion;

    @Column(name = "previous_active_version")
    private Long previousActiveVersion;

    @Column(name = "new_active_version")
    private Long newActiveVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ScheduleActorType actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    private ScheduleAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private ScheduleVersionStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30)
    private ScheduleVersionStatus newStatus;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private ScheduleAuditOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_check_status", nullable = false, length = 20)
    private ConflictCheckStatus conflictCheckStatus;

    @Column(name = "conflict_count")
    private Integer conflictCount;

    private StoreScheduleAuditEvent(
            long storeId,
            ScheduleActorType actorType,
            Long actorId,
            ScheduleAuditRecord record
    ) {
        this.storeId = storeId;
        this.scheduleStream = record.stream();
        this.targetVersion = record.targetVersion();
        this.previousActiveVersion = record.previousActiveVersion();
        this.newActiveVersion = record.newActiveVersion();
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = record.action();
        this.previousStatus = record.previousStatus();
        this.newStatus = record.newStatus();
        this.timeZoneId = record.timeZoneId();
        this.requestedAt = record.requestedAt();
        this.effectiveAt = record.effectiveAt();
        this.occurredAt = record.occurredAt();
        this.changeReason = record.changeReason();
        this.requestId = record.requestId();
        this.outcome = record.outcome();
        this.conflictCheckStatus = ConflictCheckStatus.NOT_EVALUATED;
    }

    public static StoreScheduleAuditEvent recordOperator(
            long storeId,
            long operatorId,
            ScheduleAuditRecord record
    ) {
        return new StoreScheduleAuditEvent(
                storeId,
                ScheduleActorType.STORE_OPERATOR,
                operatorId,
                record);
    }

    public static StoreScheduleAuditEvent recordSystem(
            long storeId,
            ScheduleAuditRecord record
    ) {
        return new StoreScheduleAuditEvent(
                storeId,
                ScheduleActorType.SYSTEM,
                null,
                record);
    }
}
