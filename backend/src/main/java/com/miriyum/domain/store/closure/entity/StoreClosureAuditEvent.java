package com.miriyum.domain.store.closure.entity;

import com.miriyum.domain.store.closure.model.StoreClosureActorType;
import com.miriyum.domain.store.schedule.model.ConflictCheckStatus;
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

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "previous_state", length = 30)
    private String previousState;

    @Column(name = "new_state", nullable = false, length = 30)
    private String newState;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

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
            String action,
            String previousState,
            String newState,
            String timeZoneId,
            Instant effectiveAt,
            Instant occurredAt,
            String changeReason,
            String requestId
    ) {
        if (actorType == null
                || (actorType == StoreClosureActorType.STORE_OPERATOR && actorId == null)
                || (actorType == StoreClosureActorType.SYSTEM && actorId != null)) {
            throw new IllegalArgumentException("invalid store closure audit actor");
        }
        StoreClosureAuditEvent event = new StoreClosureAuditEvent();
        event.storeId = storeId;
        event.actorType = actorType;
        event.actorId = actorId;
        event.resourceType = resourceType;
        event.resourceId = resourceId;
        event.action = action;
        event.previousState = previousState;
        event.newState = newState;
        event.timeZoneId = timeZoneId;
        event.effectiveAt = effectiveAt;
        event.occurredAt = occurredAt;
        event.changeReason = changeReason;
        event.requestId = requestId;
        event.conflictCheckStatus = ConflictCheckStatus.NOT_EVALUATED;
        return event;
    }
}
