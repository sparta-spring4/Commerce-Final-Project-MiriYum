package com.miriyum.domain.reservation.entity;

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

/** 임시 선점의 성공 상태 변경을 append-only로 보존하는 감사 스냅샷이다. */
@Entity
@Table(
        name = "reservation_hold_transition_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_hold_transition_audits_command",
                columnNames = "command_id"
        )
)
public class ReservationHoldTransitionAudit {

    private static final String SYSTEM_ACTOR = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_hold_transition_audit_id")
    private Long id;

    @Column(name = "reservation_hold_id", nullable = false)
    private Long reservationHoldId;

    @Column(name = "actor_type", nullable = false, length = 32)
    private String actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", length = 32)
    private ReservationHoldStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", nullable = false, length = 32)
    private ReservationHoldStatus afterStatus;

    @Column(name = "reservation_time_policy_version", nullable = false)
    private long reservationTimePolicyVersion;

    @Column(name = "capacity_policy_version", nullable = false)
    private long capacityPolicyVersion;

    @Column(name = "command_id", nullable = false, length = 100, unique = true)
    private String commandId;

    protected ReservationHoldTransitionAudit() {
    }

    private ReservationHoldTransitionAudit(
            long reservationHoldId,
            String actorType,
            Long actorId,
            Instant requestedAt,
            Instant occurredAt,
            ReservationHoldStatus beforeStatus,
            ReservationHoldStatus afterStatus,
            long reservationTimePolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        this.reservationHoldId = requirePositive(reservationHoldId, "reservationHoldId");
        this.actorType = requireText(actorType, 32, "actorType");
        this.actorId = requireActorId(this.actorType, actorId);
        this.requestedAt = requireNonNull(requestedAt, "requestedAt");
        this.occurredAt = requireNonNull(occurredAt, "occurredAt");
        if (this.occurredAt.isBefore(this.requestedAt)) {
            throw new IllegalArgumentException("occurredAt must not be before requestedAt");
        }
        this.beforeStatus = beforeStatus;
        this.afterStatus = requireNonNull(afterStatus, "afterStatus");
        requireValidTransition(this.beforeStatus, this.afterStatus);
        this.reservationTimePolicyVersion = requirePositive(
                reservationTimePolicyVersion,
                "reservationTimePolicyVersion"
        );
        this.capacityPolicyVersion = requirePositive(
                capacityPolicyVersion,
                "capacityPolicyVersion"
        );
        this.commandId = requireText(commandId, 100, "commandId");
    }

    /** 커밋될 선점 상태 변경과 그 판정 근거를 기록한다. */
    public static ReservationHoldTransitionAudit record(
            long reservationHoldId,
            String actorType,
            Long actorId,
            Instant requestedAt,
            Instant occurredAt,
            ReservationHoldStatus beforeStatus,
            ReservationHoldStatus afterStatus,
            long reservationTimePolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        return new ReservationHoldTransitionAudit(
                reservationHoldId,
                actorType,
                actorId,
                requestedAt,
                occurredAt,
                beforeStatus,
                afterStatus,
                reservationTimePolicyVersion,
                capacityPolicyVersion,
                commandId
        );
    }

    private static void requireValidTransition(
            ReservationHoldStatus beforeStatus,
            ReservationHoldStatus afterStatus
    ) {
        if (beforeStatus == null) {
            if (afterStatus != ReservationHoldStatus.ACTIVE) {
                throw new IllegalArgumentException("creation audit must end in ACTIVE");
            }
            return;
        }
        if (beforeStatus == afterStatus) {
            throw new IllegalArgumentException("transition audit must change status");
        }
    }

    private static Long requireActorId(String actorType, Long actorId) {
        if (SYSTEM_ACTOR.equals(actorType)) {
            if (actorId != null && actorId <= 0) {
                throw new IllegalArgumentException("actorId must be positive when present");
            }
            return actorId;
        }
        if (actorId == null || actorId <= 0) {
            throw new IllegalArgumentException("non-system actorId must be positive");
        }
        return actorId;
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must be 1 to " + maxLength + " characters");
        }
        String normalized = value.trim();
        if (normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be 1 to " + maxLength + " characters");
        }
        return normalized;
    }

    public Long getId() {
        return id;
    }

    public Long getReservationHoldId() {
        return reservationHoldId;
    }

    public String getActorType() {
        return actorType;
    }

    public Long getActorId() {
        return actorId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public ReservationHoldStatus getBeforeStatus() {
        return beforeStatus;
    }

    public ReservationHoldStatus getAfterStatus() {
        return afterStatus;
    }

    public long getReservationTimePolicyVersion() {
        return reservationTimePolicyVersion;
    }

    public long getCapacityPolicyVersion() {
        return capacityPolicyVersion;
    }

    public String getCommandId() {
        return commandId;
    }
}
