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

/** 운영자가 확정한 예약별 단일 NO_SHOW append-only 감사다. */
@Entity
@Table(
        name = "reservation_no_show_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_no_show_audits_reservation",
                columnNames = "reservation_id"
        )
)
public class ReservationNoShowAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_no_show_audit_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ReservationVisitActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 48)
    private ReservationNoShowReason reason;

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

    protected ReservationNoShowAudit() {
    }

    private ReservationNoShowAudit(
            long reservationId,
            long storeId,
            long actorId,
            ReservationNoShowReason reason,
            Instant requestedAt,
            Instant occurredAt,
            long reservationTimePolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        this.reservationId = requirePositive(reservationId, "reservationId");
        this.storeId = requirePositive(storeId, "storeId");
        this.actorType = ReservationVisitActorType.STORE_OPERATOR;
        this.actorId = requirePositive(actorId, "actorId");
        this.reason = requireNonNull(reason, "reason");
        this.requestedAt = requireNonNull(requestedAt, "requestedAt");
        this.occurredAt = requireNonNull(occurredAt, "occurredAt");
        if (this.occurredAt.isBefore(this.requestedAt)) {
            throw new IllegalArgumentException("occurredAt must not be before requestedAt");
        }
        this.beforeStatus = ReservationStatus.CONFIRMED;
        this.afterStatus = ReservationStatus.NO_SHOW;
        this.reservationTimePolicyVersion = requirePositive(
                reservationTimePolicyVersion,
                "reservationTimePolicyVersion"
        );
        this.capacityPolicyVersion = requirePositive(capacityPolicyVersion, "capacityPolicyVersion");
        this.commandId = requireCommandId(commandId);
    }

    /** 잠긴 예약의 CONFIRMED에서 NO_SHOW 전이와 필수 후보 사유를 기록한다. */
    public static ReservationNoShowAudit record(
            long reservationId,
            long storeId,
            long operatorAccountId,
            ReservationNoShowReason reason,
            Instant requestedAt,
            Instant occurredAt,
            long reservationTimePolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        return new ReservationNoShowAudit(
                reservationId,
                storeId,
                operatorAccountId,
                reason,
                requestedAt,
                occurredAt,
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
        if (value == null || value.isBlank() || value.trim().length() > 100) {
            throw new IllegalArgumentException("commandId must be 1 to 100 characters");
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public Long getStoreId() { return storeId; }
    public ReservationVisitActorType getActorType() { return actorType; }
    public Long getActorId() { return actorId; }
    public ReservationNoShowReason getReason() { return reason; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getOccurredAt() { return occurredAt; }
    public ReservationStatus getBeforeStatus() { return beforeStatus; }
    public ReservationStatus getAfterStatus() { return afterStatus; }
    public Long getReservationTimePolicyVersion() { return reservationTimePolicyVersion; }
    public Long getCapacityPolicyVersion() { return capacityPolicyVersion; }
    public String getCommandId() { return commandId; }
}
