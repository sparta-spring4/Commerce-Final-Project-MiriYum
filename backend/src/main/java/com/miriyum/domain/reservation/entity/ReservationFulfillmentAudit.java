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

/** 성공적으로 확정된 예약 방문 완료의 append-only 감사 스냅샷이다. */
@Entity
@Table(
        name = "reservation_fulfillment_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_fulfillment_audits_reservation",
                columnNames = "reservation_id"
        )
)
public class ReservationFulfillmentAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_fulfillment_audit_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ReservationFulfillmentActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", nullable = false, length = 20)
    private ReservationStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", nullable = false, length = 20)
    private ReservationStatus afterStatus;

    @Column(name = "reservation_time_policy_version", nullable = false)
    private Long reservationTimePolicyVersion;

    @Column(name = "capacity_policy_version", nullable = false)
    private Long capacityPolicyVersion;

    @Column(name = "command_id", nullable = false, length = 100)
    private String commandId;

    protected ReservationFulfillmentAudit() {
    }

    private ReservationFulfillmentAudit(
            long reservationId,
            ReservationFulfillmentActorType actorType,
            long actorId,
            Instant requestedAt,
            Instant occurredAt,
            ReservationStatus beforeStatus,
            ReservationStatus afterStatus,
            long reservationTimePolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        this.reservationId = requirePositive(reservationId, "reservationId");
        this.actorType = requireNonNull(actorType, "actorType");
        this.actorId = requirePositive(actorId, "actorId");
        this.requestedAt = requireNonNull(requestedAt, "requestedAt");
        this.occurredAt = requireNonNull(occurredAt, "occurredAt");
        if (this.occurredAt.isBefore(this.requestedAt)) {
            throw new IllegalArgumentException("occurredAt must not be before requestedAt");
        }
        this.beforeStatus = requireNonNull(beforeStatus, "beforeStatus");
        this.afterStatus = requireNonNull(afterStatus, "afterStatus");
        if (this.beforeStatus != ReservationStatus.CONFIRMED
                || this.afterStatus != ReservationStatus.FULFILLED) {
            throw new IllegalArgumentException(
                    "success audit must record CONFIRMED to FULFILLED"
            );
        }
        this.reservationTimePolicyVersion = requirePositive(
                reservationTimePolicyVersion,
                "reservationTimePolicyVersion"
        );
        this.capacityPolicyVersion = requirePositive(
                capacityPolicyVersion,
                "capacityPolicyVersion"
        );
        this.commandId = requireCommandId(commandId);
    }

    public static ReservationFulfillmentAudit recordSuccess(
            long reservationId,
            ReservationFulfillmentActorType actorType,
            long actorId,
            Instant requestedAt,
            Instant occurredAt,
            ReservationStatus beforeStatus,
            ReservationStatus afterStatus,
            long reservationTimePolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        return new ReservationFulfillmentAudit(
                reservationId,
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

    private static String requireCommandId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("commandId must be 1 to 100 characters");
        }
        String normalized = value.trim();
        if (normalized.isBlank() || normalized.length() > 100) {
            throw new IllegalArgumentException("commandId must be 1 to 100 characters");
        }
        return normalized;
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public ReservationFulfillmentActorType getActorType() {
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

    public ReservationStatus getBeforeStatus() {
        return beforeStatus;
    }

    public ReservationStatus getAfterStatus() {
        return afterStatus;
    }

    public Long getReservationTimePolicyVersion() {
        return reservationTimePolicyVersion;
    }

    public Long getCapacityPolicyVersion() {
        return capacityPolicyVersion;
    }

    public String getCommandId() {
        return commandId;
    }
}
