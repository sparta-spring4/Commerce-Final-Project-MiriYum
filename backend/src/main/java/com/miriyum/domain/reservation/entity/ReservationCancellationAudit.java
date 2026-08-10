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

/**
 * 성공적으로 확정된 예약 취소의 append-only 감사 스냅샷이다.
 */
@Entity
@Table(
        name = "reservation_cancellation_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_cancellation_audits_reservation",
                columnNames = "reservation_id"
        )
)
public class ReservationCancellationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_cancellation_audit_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ReservationCancellationActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

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

    @Column(name = "cancellation_policy_version", nullable = false)
    private Long cancellationPolicyVersion;

    @Column(name = "capacity_policy_version", nullable = false)
    private Long capacityPolicyVersion;

    @Column(name = "command_id", nullable = false, length = 100)
    private String commandId;

    protected ReservationCancellationAudit() {
    }

    private ReservationCancellationAudit(
            long reservationId,
            ReservationCancellationActorType actorType,
            long actorId,
            String cancellationReason,
            Instant requestedAt,
            Instant occurredAt,
            ReservationStatus beforeStatus,
            ReservationStatus afterStatus,
            long cancellationPolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        this.reservationId = requirePositive(reservationId, "reservationId");
        this.actorType = requireNonNull(actorType, "actorType");
        this.actorId = requirePositive(actorId, "actorId");
        this.cancellationReason = requireReason(this.actorType, cancellationReason);
        this.requestedAt = requireNonNull(requestedAt, "requestedAt");
        this.occurredAt = requireNonNull(occurredAt, "occurredAt");
        if (this.occurredAt.isBefore(this.requestedAt)) {
            throw new IllegalArgumentException("occurredAt must not be before requestedAt");
        }
        this.beforeStatus = requireNonNull(beforeStatus, "beforeStatus");
        this.afterStatus = requireNonNull(afterStatus, "afterStatus");
        if (this.beforeStatus != ReservationStatus.CONFIRMED
                || this.afterStatus != ReservationStatus.CANCELLED) {
            throw new IllegalArgumentException("success audit must record CONFIRMED to CANCELLED");
        }
        this.cancellationPolicyVersion = requirePositive(
                cancellationPolicyVersion,
                "cancellationPolicyVersion"
        );
        this.capacityPolicyVersion = requirePositive(capacityPolicyVersion, "capacityPolicyVersion");
        this.commandId = requireCommandId(commandId);
    }

    /**
     * 확정 예약이 취소된 직후의 성공 감사 스냅샷을 만든다.
     *
     * @throws IllegalArgumentException 성공 취소 불변식에 맞지 않는 값이 전달된 경우
     */
    public static ReservationCancellationAudit recordSuccess(
            long reservationId,
            ReservationCancellationActorType actorType,
            long actorId,
            String cancellationReason,
            Instant requestedAt,
            Instant occurredAt,
            ReservationStatus beforeStatus,
            ReservationStatus afterStatus,
            long cancellationPolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        return new ReservationCancellationAudit(
                reservationId,
                actorType,
                actorId,
                cancellationReason,
                requestedAt,
                occurredAt,
                beforeStatus,
                afterStatus,
                cancellationPolicyVersion,
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

    private static String requireReason(
            ReservationCancellationActorType actorType,
            String cancellationReason
    ) {
        if (actorType == ReservationCancellationActorType.STORE_OPERATOR
                && cancellationReason == null) {
            throw new IllegalArgumentException("store operator cancellation requires a reason");
        }
        if (cancellationReason != null
                && !hasValidReasonLength(cancellationReason)) {
            throw new IllegalArgumentException("cancellationReason must be 1 to 500 characters");
        }
        return cancellationReason;
    }

    private static boolean hasValidReasonLength(String reason) {
        int length = reason.codePointCount(0, reason.length());
        return length >= 1 && length <= 500;
    }

    private static String requireCommandId(String commandId) {
        if (commandId == null || commandId.isBlank() || commandId.length() > 100) {
            throw new IllegalArgumentException("commandId must be 1 to 100 characters");
        }
        return commandId;
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public ReservationCancellationActorType getActorType() {
        return actorType;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getCancellationReason() {
        return cancellationReason;
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

    public Long getCancellationPolicyVersion() {
        return cancellationPolicyVersion;
    }

    public Long getCapacityPolicyVersion() {
        return capacityPolicyVersion;
    }

    public String getCommandId() {
        return commandId;
    }
}
