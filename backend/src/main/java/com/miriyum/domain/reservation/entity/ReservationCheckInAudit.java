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

/** raw QR·digest·opaque epoch를 제외한 QR 발급·완료 append-only 감사다. */
@Entity
@Table(
        name = "reservation_check_in_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_check_in_audits_event_version",
                columnNames = {"reservation_id", "event_type", "token_version"}
        )
)
public class ReservationCheckInAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_check_in_audit_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private ReservationCheckInEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ReservationVisitActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "token_version", nullable = false)
    private Long tokenVersion;

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

    @Column(name = "command_id", nullable = false, length = 100)
    private String commandId;

    protected ReservationCheckInAudit() {
    }

    private ReservationCheckInAudit(
            long reservationId,
            long storeId,
            ReservationCheckInEventType eventType,
            ReservationVisitActorType actorType,
            long actorId,
            long tokenVersion,
            Instant requestedAt,
            Instant occurredAt,
            ReservationStatus beforeStatus,
            ReservationStatus afterStatus,
            String commandId
    ) {
        this.reservationId = requirePositive(reservationId, "reservationId");
        this.storeId = requirePositive(storeId, "storeId");
        this.eventType = requireNonNull(eventType, "eventType");
        this.actorType = requireNonNull(actorType, "actorType");
        this.actorId = requirePositive(actorId, "actorId");
        this.tokenVersion = requirePositive(tokenVersion, "tokenVersion");
        this.requestedAt = requireNonNull(requestedAt, "requestedAt");
        this.occurredAt = requireNonNull(occurredAt, "occurredAt");
        if (this.occurredAt.isBefore(this.requestedAt)) {
            throw new IllegalArgumentException("occurredAt must not be before requestedAt");
        }
        this.beforeStatus = requireNonNull(beforeStatus, "beforeStatus");
        this.afterStatus = requireNonNull(afterStatus, "afterStatus");
        this.commandId = requireCommandId(commandId);
        validateEventContract();
    }

    /** 소비자가 본인 예약의 새 QR version을 발급한 사건을 기록한다. */
    public static ReservationCheckInAudit recordGrantIssued(
            long reservationId,
            long storeId,
            long consumerAccountId,
            long tokenVersion,
            Instant requestedAt,
            Instant occurredAt,
            String commandId
    ) {
        return new ReservationCheckInAudit(
                reservationId,
                storeId,
                ReservationCheckInEventType.QR_GRANT_ISSUED,
                ReservationVisitActorType.CONSUMER,
                consumerAccountId,
                tokenVersion,
                requestedAt,
                occurredAt,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CONFIRMED,
                commandId
        );
    }

    /** 매장 운영자가 current QR를 소비해 방문 완료한 사건을 기록한다. */
    public static ReservationCheckInAudit recordQrFulfilled(
            long reservationId,
            long storeId,
            long operatorAccountId,
            long tokenVersion,
            Instant requestedAt,
            Instant occurredAt,
            String commandId
    ) {
        return new ReservationCheckInAudit(
                reservationId,
                storeId,
                ReservationCheckInEventType.QR_CHECK_IN_FULFILLED,
                ReservationVisitActorType.STORE_OPERATOR,
                operatorAccountId,
                tokenVersion,
                requestedAt,
                occurredAt,
                ReservationStatus.CONFIRMED,
                ReservationStatus.FULFILLED,
                commandId
        );
    }

    private void validateEventContract() {
        boolean grantIssued = eventType == ReservationCheckInEventType.QR_GRANT_ISSUED
                && actorType == ReservationVisitActorType.CONSUMER
                && beforeStatus == ReservationStatus.CONFIRMED
                && afterStatus == ReservationStatus.CONFIRMED;
        boolean qrFulfilled = eventType == ReservationCheckInEventType.QR_CHECK_IN_FULFILLED
                && actorType == ReservationVisitActorType.STORE_OPERATOR
                && beforeStatus == ReservationStatus.CONFIRMED
                && afterStatus == ReservationStatus.FULFILLED;
        if (!grantIssued && !qrFulfilled) {
            throw new IllegalArgumentException("check-in audit event contract is invalid");
        }
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
    public ReservationCheckInEventType getEventType() { return eventType; }
    public ReservationVisitActorType getActorType() { return actorType; }
    public Long getActorId() { return actorId; }
    public Long getTokenVersion() { return tokenVersion; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getOccurredAt() { return occurredAt; }
    public ReservationStatus getBeforeStatus() { return beforeStatus; }
    public ReservationStatus getAfterStatus() { return afterStatus; }
    public String getCommandId() { return commandId; }
}
