package com.miriyum.domain.schedule.closure.entity;

import com.miriyum.domain.schedule.closure.model.StoreClosureActorType;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureReason;
import com.miriyum.domain.schedule.model.ConflictCheckStatus;
import com.miriyum.domain.schedule.model.ScheduleAuditOutcome;
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

@Entity
@Table(name = "store_closure_audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreClosureAuditEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "closure_audit_event_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private StoreClosureActorType actorType;

    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 100)
    private String resourceId;

    @Column(name = "previous_active_version")
    private Long previousActiveVersion;

    @Column(name = "new_active_version")
    private Long newActiveVersion;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "previous_state", length = 30)
    private String previousState;

    @Column(name = "new_state", nullable = false, length = 30)
    private String newState;

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

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private ScheduleAuditOutcome outcome;

    @Column(name = "closure_start_at")
    private Instant closureStartAt;

    @Column(name = "previous_closure_end_at")
    private Instant previousClosureEndAt;

    @Column(name = "new_closure_end_at")
    private Instant newClosureEndAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "temporary_closure_reason", length = 30)
    private TemporaryClosureReason temporaryClosureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_check_status", nullable = false, length = 20)
    private ConflictCheckStatus conflictCheckStatus;

    @Column(name = "conflict_count")
    private Integer conflictCount;

    public static StoreClosureAuditEvent record(
            long storeId,
            StoreClosureActorType actorType,
            Long actorId,
            String resourceType,
            String resourceId,
            Long previousActiveVersion,
            Long newActiveVersion,
            String action,
            String previousState,
            String newState,
            String timeZoneId,
            Instant requestedAt,
            Instant effectiveAt,
            Instant occurredAt,
            String changeReason,
            String requestId,
            ScheduleAuditOutcome outcome,
            Instant closureStartAt,
            Instant previousClosureEndAt,
            Instant newClosureEndAt,
            TemporaryClosureReason temporaryClosureReason
    ) {
        if (actorType == null
                || (actorType == StoreClosureActorType.STORE_OPERATOR && actorId == null)
                || (actorType == StoreClosureActorType.SYSTEM && actorId != null)) {
            throw new IllegalArgumentException("invalid store closure audit actor");
        }
        if (!"REGULAR".equals(resourceType) && !"TEMPORARY".equals(resourceType)) {
            throw new IllegalArgumentException("invalid store closure audit resource type");
        }
        boolean temporary = "TEMPORARY".equals(resourceType);
        if ((temporary && (previousActiveVersion != null || newActiveVersion != null
                || closureStartAt == null || newClosureEndAt == null
                || temporaryClosureReason == null))
                || (!temporary && (closureStartAt != null || previousClosureEndAt != null
                || newClosureEndAt != null || temporaryClosureReason != null))) {
            throw new IllegalArgumentException("invalid temporary closure audit metadata");
        }
        StoreClosureAuditEvent event = new StoreClosureAuditEvent();
        event.storeId = storeId;
        event.actorType = actorType;
        event.actorId = actorId;
        event.resourceType = resourceType;
        event.resourceId = resourceId;
        event.previousActiveVersion = previousActiveVersion;
        event.newActiveVersion = newActiveVersion;
        event.action = action;
        event.previousState = previousState;
        event.newState = newState;
        event.timeZoneId = timeZoneId;
        event.requestedAt = requestedAt;
        event.effectiveAt = effectiveAt;
        event.occurredAt = occurredAt;
        event.changeReason = changeReason;
        event.requestId = requestId;
        event.outcome = outcome;
        event.closureStartAt = closureStartAt;
        event.previousClosureEndAt = previousClosureEndAt;
        event.newClosureEndAt = newClosureEndAt;
        event.temporaryClosureReason = temporaryClosureReason;
        event.conflictCheckStatus = ConflictCheckStatus.NOT_EVALUATED;
        return event;
    }
}
