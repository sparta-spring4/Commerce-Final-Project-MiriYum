package com.miriyum.domain.reservation.entity;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Reservation terminal 복구 상태를 Payment에 at-least-once 전달하는 outbox 행이다. */
@Entity
@Table(
        name = "reservation_payment_recovery_outbox",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_reservation_payment_recovery_outbox_source",
                    columnNames = {"source_type", "source_id"}),
            @UniqueConstraint(
                    name = "uk_reservation_payment_recovery_outbox_delivery",
                    columnNames = "delivery_idempotency_key")
        })
public class ReservationPaymentRecoveryOutbox {

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public enum Status {
        PENDING,
        PROCESSING,
        DELIVERED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_payment_recovery_outbox_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 48, updatable = false)
    private ManualRecoverySourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 64, updatable = false)
    private String sourceId;

    @Column(name = "payment_id", nullable = false, length = 64, updatable = false)
    private String paymentId;

    @Column(name = "source_event_id", nullable = false, length = 100, updatable = false)
    private String sourceEventId;

    @Column(name = "delivery_idempotency_key", nullable = false, length = 36, updatable = false)
    private String deliveryIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "claim_token", nullable = false)
    private long claimToken;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ReservationPaymentRecoveryOutbox() {
    }

    public static ReservationPaymentRecoveryOutbox pending(
            ManualRecoverySourceType sourceType,
            String sourceId,
            String paymentId,
            String sourceEventId,
            String deliveryIdempotencyKey,
            Instant now
    ) {
        ReservationPaymentRecoveryOutbox outbox = new ReservationPaymentRecoveryOutbox();
        outbox.sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        outbox.sourceId = requirePublicId(sourceId, "sourceId");
        outbox.paymentId = requirePublicId(paymentId, "paymentId");
        outbox.sourceEventId = requireText(sourceEventId, 100, "sourceEventId");
        outbox.deliveryIdempotencyKey = requireUuid(
                deliveryIdempotencyKey, "deliveryIdempotencyKey");
        outbox.status = Status.PENDING;
        outbox.nextAttemptAt = Objects.requireNonNull(now, "now must not be null");
        outbox.createdAt = now;
        outbox.updatedAt = now;
        return outbox;
    }

    public void claim(String owner, Instant now, Instant until) {
        requireLease(owner, now, until);
        boolean pending = status == Status.PENDING
                && nextAttemptAt != null
                && !nextAttemptAt.isAfter(now);
        boolean expired = status == Status.PROCESSING
                && leaseUntil != null
                && !leaseUntil.isAfter(now);
        if (!pending && !expired) {
            throw new IllegalStateException("recovery outbox is not claimable");
        }
        status = Status.PROCESSING;
        attemptCount++;
        claimToken++;
        nextAttemptAt = null;
        leaseOwner = owner;
        leaseUntil = until;
        updatedAt = now;
    }

    public void deliver(String owner, long token, Instant now) {
        requireFence(owner, token, now);
        status = Status.DELIVERED;
        deliveredAt = now;
        updatedAt = now;
        clearLease();
    }

    public void requeue(String owner, long token, Instant now, Duration delay) {
        requireFence(owner, token, now);
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        status = Status.PENDING;
        nextAttemptAt = now.plus(delay);
        updatedAt = now;
        clearLease();
    }

    public boolean matches(
            String paymentId,
            String sourceEventId,
            String deliveryIdempotencyKey
    ) {
        return Objects.equals(this.paymentId, paymentId)
                && Objects.equals(this.sourceEventId, sourceEventId)
                && Objects.equals(this.deliveryIdempotencyKey, deliveryIdempotencyKey);
    }

    private void requireFence(String owner, long token, Instant now) {
        if (status != Status.PROCESSING
                || owner == null
                || !owner.equals(leaseOwner)
                || token != claimToken
                || now == null
                || leaseUntil == null
                || !leaseUntil.isAfter(now)) {
            throw new IllegalStateException("stale recovery outbox claim");
        }
    }

    private static void requireLease(String owner, Instant now, Instant until) {
        if (owner == null || owner.isBlank() || owner.length() > 64
                || now == null || until == null || !until.isAfter(now)) {
            throw new IllegalArgumentException("lease fields must be valid");
        }
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
    }

    private static String requirePublicId(String value, String field) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalArgumentException(field + " must be a positive numeric string");
        }
        return value;
    }

    private static String requireUuid(String value, String field) {
        if (value == null || !UUID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a normalized UUID");
        }
        return value;
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain valid text");
        }
        return value;
    }

    public Long getId() { return id; }
    public ManualRecoverySourceType getSourceType() { return sourceType; }
    public String getSourceId() { return sourceId; }
    public String getPaymentId() { return paymentId; }
    public String getSourceEventId() { return sourceEventId; }
    public String getDeliveryIdempotencyKey() { return deliveryIdempotencyKey; }
    public Status getStatus() { return status; }
    public long getClaimToken() { return claimToken; }
}
