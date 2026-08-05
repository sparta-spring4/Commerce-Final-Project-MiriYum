package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 시간 정책 lifecycle 명령의 append-only 감사 원장이다.
 */
@Entity
@Table(name = "reservation_time_policy_audits")
public class ReservationTimePolicyAudit {

    public enum ActorType {
        STORE_OPERATOR,
        SYSTEM
    }

    public enum ConflictCheckStatus {
        NOT_EVALUATED,
        EVALUATED
    }

    public enum Outcome {
        SUCCEEDED,
        ACTIVATION_FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_time_policy_audit_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 30)
    private ActorType actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "target_version", nullable = false)
    private Long targetVersion;

    @Column(name = "previous_active_version")
    private Long previousActiveVersion;

    @Column(name = "new_active_version")
    private Long newActiveVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", length = 30)
    private ReservationTimePolicyStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", nullable = false, length = 30)
    private ReservationTimePolicyStatus afterStatus;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private Outcome outcome;

    @Column(name = "command_id", nullable = false, length = 128)
    private String commandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_check_status", nullable = false, length = 30)
    private ConflictCheckStatus conflictCheckStatus;

    @Column(name = "conflict_count")
    private Long conflictCount;

    protected ReservationTimePolicyAudit() {
    }

    private ReservationTimePolicyAudit(
            long storeId,
            ActorType actorType,
            Long actorId,
            long targetVersion,
            Long previousActiveVersion,
            Long newActiveVersion,
            ReservationTimePolicyStatus beforeStatus,
            ReservationTimePolicyStatus afterStatus,
            Instant requestedAt,
            Instant effectiveAt,
            Instant occurredAt,
            String changeReason,
            Outcome outcome,
            String commandId
    ) {
        if (storeId <= 0 || targetVersion <= 0 || actorType == null
                || afterStatus == null || requestedAt == null
                || occurredAt == null || outcome == null
                || commandId == null || commandId.isBlank()
                || commandId.length() > 128) {
            throw new IllegalArgumentException("valid time policy audit fields are required");
        }
        if (actorType == ActorType.STORE_OPERATOR
                && (actorId == null || actorId <= 0)) {
            throw new IllegalArgumentException("store operator audit requires actorId");
        }
        if (changeReason != null
                && (changeReason.isBlank() || changeReason.length() > 500)) {
            throw new IllegalArgumentException("changeReason must be 1 to 500 characters");
        }
        this.storeId = storeId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.targetVersion = targetVersion;
        this.previousActiveVersion = previousActiveVersion;
        this.newActiveVersion = newActiveVersion;
        this.beforeStatus = beforeStatus;
        this.afterStatus = afterStatus;
        this.requestedAt = requestedAt;
        this.effectiveAt = effectiveAt;
        this.occurredAt = occurredAt;
        this.changeReason = changeReason;
        this.outcome = outcome;
        this.commandId = commandId;
        this.conflictCheckStatus = ConflictCheckStatus.NOT_EVALUATED;
        this.conflictCount = null;
    }

    public static ReservationTimePolicyAudit operatorCommand(
            long storeId,
            long actorId,
            long targetVersion,
            Long previousActiveVersion,
            Long newActiveVersion,
            ReservationTimePolicyStatus beforeStatus,
            ReservationTimePolicyStatus afterStatus,
            Instant requestedAt,
            Instant effectiveAt,
            Instant occurredAt,
            String changeReason,
            String commandId
    ) {
        return new ReservationTimePolicyAudit(
                storeId,
                ActorType.STORE_OPERATOR,
                actorId,
                targetVersion,
                previousActiveVersion,
                newActiveVersion,
                beforeStatus,
                afterStatus,
                requestedAt,
                effectiveAt,
                occurredAt,
                changeReason,
                Outcome.SUCCEEDED,
                commandId
        );
    }

    public static ReservationTimePolicyAudit systemActivation(
            long storeId,
            long targetVersion,
            Long previousActiveVersion,
            Long newActiveVersion,
            ReservationTimePolicyStatus beforeStatus,
            ReservationTimePolicyStatus afterStatus,
            Instant requestedAt,
            Instant effectiveAt,
            Instant occurredAt,
            String changeReason,
            Outcome outcome,
            String commandId
    ) {
        return new ReservationTimePolicyAudit(
                storeId,
                ActorType.SYSTEM,
                null,
                targetVersion,
                previousActiveVersion,
                newActiveVersion,
                beforeStatus,
                afterStatus,
                requestedAt,
                effectiveAt,
                occurredAt,
                changeReason,
                outcome,
                commandId
        );
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public Long getActorId() {
        return actorId;
    }

    public Long getTargetVersion() {
        return targetVersion;
    }

    public Long getPreviousActiveVersion() {
        return previousActiveVersion;
    }

    public Long getNewActiveVersion() {
        return newActiveVersion;
    }

    public ReservationTimePolicyStatus getBeforeStatus() {
        return beforeStatus;
    }

    public ReservationTimePolicyStatus getAfterStatus() {
        return afterStatus;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getCommandId() {
        return commandId;
    }

    public ConflictCheckStatus getConflictCheckStatus() {
        return conflictCheckStatus;
    }

    public Long getConflictCount() {
        return conflictCount;
    }
}
