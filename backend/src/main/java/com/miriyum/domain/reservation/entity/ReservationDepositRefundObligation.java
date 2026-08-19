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
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Durable full-deposit refund obligation owned by Reservation. */
@Entity
@Table(
        name = "reservation_deposit_refund_obligations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_deposit_refund_obligation_identity",
                columnNames = {"reservation_deposit_process_id", "payment_id", "reason_code"}
        )
)
public class ReservationDepositRefundObligation {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public enum Status {
        REQUIRED,
        PROCESSING,
        COMPLETED,
        RECONCILIATION_REQUIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_deposit_refund_obligation_id")
    private Long id;
    @Column(name = "reservation_deposit_process_id", nullable = false, updatable = false)
    private long reservationDepositProcessId;
    @Column(name = "payment_id", nullable = false, length = 64, updatable = false)
    private String paymentId;
    @Column(name = "refund_amount_minor", nullable = false, updatable = false)
    private long refundAmountMinor;
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;
    @Column(name = "refund_policy_version", nullable = false, updatable = false)
    private long refundPolicyVersion;
    @Column(name = "source_event_id", nullable = false, length = 100, updatable = false)
    private String sourceEventId;
    @Column(name = "idempotency_key", nullable = false, length = 36, updatable = false)
    private String idempotencyKey;
    @Column(name = "reason_code", nullable = false, length = 40, updatable = false)
    private String reasonCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
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
    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ReservationDepositRefundObligation() {
    }

    public static ReservationDepositRefundObligation required(
            long processId,
            String paymentId,
            long refundAmountMinor,
            String currency,
            long refundPolicyVersion,
            String sourceEventId,
            String idempotencyKey,
            String reasonCode,
            Instant now
    ) {
        ReservationDepositRefundObligation obligation =
                new ReservationDepositRefundObligation();
        obligation.reservationDepositProcessId = requirePositive(processId, "processId");
        obligation.paymentId = requireText(paymentId, 64, "paymentId");
        obligation.refundAmountMinor = requirePositive(
                refundAmountMinor, "refundAmountMinor");
        obligation.currency = requirePattern(currency, CURRENCY, "currency");
        obligation.refundPolicyVersion = requirePositive(
                refundPolicyVersion, "refundPolicyVersion");
        obligation.sourceEventId = requireText(sourceEventId, 100, "sourceEventId");
        obligation.idempotencyKey = requirePattern(
                idempotencyKey, IDEMPOTENCY_KEY, "idempotencyKey");
        obligation.reasonCode = requireText(reasonCode, 40, "reasonCode");
        obligation.status = Status.REQUIRED;
        obligation.nextAttemptAt = Objects.requireNonNull(now, "now must not be null");
        obligation.createdAt = now;
        return obligation;
    }

    public boolean matchesRequired(
            long processId,
            String paymentId,
            long refundAmountMinor,
            String currency,
            long refundPolicyVersion,
            String sourceEventId,
            String idempotencyKey,
            String reasonCode
    ) {
        return reservationDepositProcessId == processId
                && this.refundAmountMinor == refundAmountMinor
                && this.refundPolicyVersion == refundPolicyVersion
                && Objects.equals(this.paymentId, paymentId)
                && Objects.equals(this.currency, currency)
                && Objects.equals(this.sourceEventId, sourceEventId)
                && Objects.equals(this.idempotencyKey, idempotencyKey)
                && Objects.equals(this.reasonCode, reasonCode);
    }

    public void claim(String owner, Instant now, Instant until) {
        requireLease(owner, now, until);
        boolean claimable = (status == Status.REQUIRED
                || status == Status.RECONCILIATION_REQUIRED)
                && nextAttemptAt != null
                && !nextAttemptAt.isAfter(now);
        boolean reclaimable = status == Status.PROCESSING
                && leaseUntil != null
                && !leaseUntil.isAfter(now);
        if (!claimable && !reclaimable) {
            throw new IllegalStateException("refund obligation is not claimable");
        }
        status = Status.PROCESSING;
        attemptCount++;
        claimToken++;
        leaseOwner = owner;
        leaseUntil = until;
        lastAttemptedAt = now;
        nextAttemptAt = null;
    }

    public boolean isOwnedBy(String owner, long token, Instant now) {
        return owner != null
                && now != null
                && status == Status.PROCESSING
                && claimToken == token
                && owner.equals(leaseOwner)
                && leaseUntil != null
                && leaseUntil.isAfter(now);
    }

    public void complete(String owner, long token, Instant now) {
        requireFence(owner, token, now);
        status = Status.COMPLETED;
        completedAt = now;
        clearLease();
    }

    public void requeue(String owner, long token, Instant now, Duration delay) {
        requireFence(owner, token, now);
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        status = Status.REQUIRED;
        nextAttemptAt = now.plus(delay);
        clearLease();
    }

    public void requireReconciliation(String owner, long token, Instant now) {
        requireFence(owner, token, now);
        status = Status.RECONCILIATION_REQUIRED;
        completedAt = now;
        clearLease();
    }

    public void scheduleReconciliation(
            String owner,
            long token,
            Instant now,
            Duration delay
    ) {
        requireFence(owner, token, now);
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        status = Status.RECONCILIATION_REQUIRED;
        nextAttemptAt = now.plus(delay);
        completedAt = null;
        clearLease();
    }

    private void requireFence(String owner, long token, Instant now) {
        if (!isOwnedBy(owner, token, now)) {
            throw new IllegalStateException("stale refund obligation claim");
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

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requirePattern(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return value;
    }

    private static String requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain valid text");
        }
        return value;
    }

    public Long getId() { return id; }
    public long getReservationDepositProcessId() { return reservationDepositProcessId; }
    public String getPaymentId() { return paymentId; }
    public long getRefundAmountMinor() { return refundAmountMinor; }
    public String getCurrency() { return currency; }
    public long getRefundPolicyVersion() { return refundPolicyVersion; }
    public String getSourceEventId() { return sourceEventId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getReasonCode() { return reasonCode; }
    public Status getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public long getClaimToken() { return claimToken; }
    public Instant getLastAttemptedAt() { return lastAttemptedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getRowVersion() { return rowVersion; }
}
