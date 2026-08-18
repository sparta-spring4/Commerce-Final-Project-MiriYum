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

/** Durable Reservation-owned obligation for one Payment deposit disposition event. */
@Entity
@Table(
        name = "reservation_deposit_disposition_obligations",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_reservation_deposit_disposition_payment_event",
                    columnNames = {"payment_id", "source_event_id"}),
            @UniqueConstraint(
                    name = "uk_reservation_deposit_disposition_obligation_key",
                    columnNames = "obligation_key"),
            @UniqueConstraint(
                    name = "uk_reservation_deposit_disposition_cancellation_key",
                    columnNames = "cancellation_idempotency_key")
        }
)
public class ReservationDepositDispositionObligation {

    private static final Pattern PUBLIC_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        RECONCILIATION_REQUIRED,
        RECOVERY_REQUIRED
    }

    public enum Operation {
        APPLY,
        QUERY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_deposit_disposition_obligation_id")
    private Long id;
    @Column(name = "reservation_deposit_process_id", nullable = false, updatable = false)
    private long reservationDepositProcessId;
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private long reservationId;
    @Column(name = "payment_id", nullable = false, length = 64, updatable = false)
    private String paymentId;
    @Column(name = "source_event_id", nullable = false, length = 100, updatable = false)
    private String sourceEventId;
    @Column(name = "source_event_type", nullable = false, length = 40, updatable = false)
    private String sourceEventType;
    @Column(name = "corrects_source_event_id", length = 100, updatable = false)
    private String correctsSourceEventId;
    @Column(name = "policy_version", nullable = false, updatable = false)
    private long policyVersion;
    @Column(name = "responsibility_code", nullable = false, length = 32, updatable = false)
    private String responsibilityCode;
    @Column(name = "target_refund_rate_basis_points", nullable = false, updatable = false)
    private int targetRefundRateBasisPoints;
    @Column(name = "obligation_key", nullable = false, length = 36, updatable = false)
    private String obligationKey;
    @Column(
            name = "cancellation_idempotency_key",
            nullable = false,
            length = 36,
            updatable = false)
    private String cancellationIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;
    @Enumerated(EnumType.STRING)
    @Column(name = "next_operation", nullable = false, length = 8)
    private Operation nextOperation;
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

    @Column(name = "disposition_id", length = 64)
    private String dispositionId;
    @Column(name = "refund_id", length = 64)
    private String refundId;
    @Column(name = "original_amount_minor")
    private Long originalAmountMinor;
    @Column(name = "target_refund_amount_minor")
    private Long targetRefundAmountMinor;
    @Column(name = "incremental_refund_amount_minor")
    private Long incrementalRefundAmountMinor;
    @Column(name = "completed_refund_amount_minor")
    private Long completedRefundAmountMinor;
    @Column(name = "withheld_amount_minor")
    private Long withheldAmountMinor;
    @Column(name = "currency", length = 3)
    private String currency;
    @Column(name = "payment_disposition_status", length = 32)
    private String paymentDispositionStatus;
    @Column(name = "failure_classification", length = 16)
    private String failureClassification;
    @Column(name = "payment_requested_at")
    private Instant paymentRequestedAt;
    @Column(name = "payment_updated_at")
    private Instant paymentUpdatedAt;
    @Column(name = "payment_completed_at")
    private Instant paymentCompletedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ReservationDepositDispositionObligation() {
    }

    public static ReservationDepositDispositionObligation pending(
            long processId,
            long reservationId,
            String paymentId,
            String sourceEventId,
            String sourceEventType,
            String correctsSourceEventId,
            long policyVersion,
            String responsibilityCode,
            int targetRefundRateBasisPoints,
            String obligationKey,
            String cancellationIdempotencyKey,
            Instant now
    ) {
        ReservationDepositDispositionObligation obligation =
                new ReservationDepositDispositionObligation();
        obligation.reservationDepositProcessId = requirePositive(processId, "processId");
        obligation.reservationId = requirePositive(reservationId, "reservationId");
        obligation.paymentId = requirePattern(paymentId, PUBLIC_ID, "paymentId");
        obligation.sourceEventId = requireText(sourceEventId, 100, "sourceEventId");
        obligation.sourceEventType = requireText(sourceEventType, 40, "sourceEventType");
        obligation.correctsSourceEventId = correctsSourceEventId == null
                ? null
                : requireText(correctsSourceEventId, 100, "correctsSourceEventId");
        if (policyVersion != 2L) {
            throw new IllegalArgumentException("policyVersion must be 2");
        }
        obligation.policyVersion = policyVersion;
        obligation.responsibilityCode = requireResponsibility(responsibilityCode);
        obligation.targetRefundRateBasisPoints = requireTargetRate(
                targetRefundRateBasisPoints);
        obligation.obligationKey = requirePattern(obligationKey, UUID, "obligationKey");
        obligation.cancellationIdempotencyKey = requirePattern(
                cancellationIdempotencyKey, UUID, "cancellationIdempotencyKey");
        Instant created = Objects.requireNonNull(now, "now must not be null");
        obligation.status = Status.PENDING;
        obligation.nextOperation = Operation.APPLY;
        obligation.nextAttemptAt = created;
        obligation.createdAt = created;
        obligation.updatedAt = created;
        return obligation;
    }

    public void claim(String owner, Instant now, Instant until) {
        requireLease(owner, now, until);
        boolean due = (status == Status.PENDING
                || status == Status.RECONCILIATION_REQUIRED)
                && nextAttemptAt != null
                && !nextAttemptAt.isAfter(now);
        boolean expired = status == Status.PROCESSING
                && leaseUntil != null
                && !leaseUntil.isAfter(now);
        if (!due && !expired) {
            throw new IllegalStateException("disposition obligation is not claimable");
        }
        status = Status.PROCESSING;
        attemptCount++;
        claimToken++;
        leaseOwner = owner;
        leaseUntil = until;
        lastAttemptedAt = now;
        nextAttemptAt = null;
        updatedAt = now;
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

    public void complete(
            String owner,
            long token,
            Instant now,
            PaymentSnapshot snapshot
    ) {
        requireFence(owner, token, now);
        PaymentSnapshot validated = Objects.requireNonNull(
                snapshot, "snapshot must not be null");
        if (!"COMPLETED".equals(validated.status())
                || validated.failureClassification() != null) {
            throw new IllegalArgumentException("completed snapshot is required");
        }
        applySnapshot(validated);
        status = Status.COMPLETED;
        completedAt = now;
        updatedAt = now;
        nextAttemptAt = null;
        clearLease();
    }

    public void requeue(
            String owner,
            long token,
            Instant now,
            Duration delay
    ) {
        requeue(owner, token, now, delay, null);
    }

    public void requeue(
            String owner,
            long token,
            Instant now,
            Duration delay,
            PaymentSnapshot snapshot
    ) {
        requireFence(owner, token, now);
        requireDelay(delay);
        if (snapshot != null) {
            applySnapshot(snapshot);
        }
        status = Status.PENDING;
        nextOperation = Operation.APPLY;
        nextAttemptAt = now.plus(delay);
        updatedAt = now;
        clearLease();
    }

    public void requeueQuery(
            String owner,
            long token,
            Instant now,
            Duration delay,
            PaymentSnapshot snapshot
    ) {
        requireFence(owner, token, now);
        requireDelay(delay);
        if (snapshot != null) {
            applySnapshot(snapshot);
        }
        status = Status.RECONCILIATION_REQUIRED;
        nextOperation = Operation.QUERY;
        nextAttemptAt = now.plus(delay);
        updatedAt = now;
        clearLease();
    }

    public void scheduleQuery(
            String owner,
            long token,
            Instant now,
            Duration delay,
            PaymentSnapshot snapshot
    ) {
        requireFence(owner, token, now);
        requireDelay(delay);
        PaymentSnapshot validated = Objects.requireNonNull(
                snapshot, "snapshot must not be null");
        if (!"PROCESSING".equals(validated.status())
                || validated.failureClassification() != null) {
            throw new IllegalArgumentException("processing snapshot is required");
        }
        applySnapshot(validated);
        if (attemptCount < 1) {
            throw new IllegalStateException("a processing query requires a claimed attempt");
        }
        attemptCount--;
        status = Status.RECONCILIATION_REQUIRED;
        nextOperation = Operation.QUERY;
        nextAttemptAt = now.plus(delay);
        updatedAt = now;
        clearLease();
    }

    public void requireReconciliation(
            String owner,
            long token,
            Instant now,
            Duration delay,
            PaymentSnapshot snapshot
    ) {
        requireFence(owner, token, now);
        requireDelay(delay);
        PaymentSnapshot validated = Objects.requireNonNull(
                snapshot, "snapshot must not be null");
        if (!"RECONCILIATION_REQUIRED".equals(validated.status())
                || !"UNKNOWN".equals(validated.failureClassification())) {
            throw new IllegalArgumentException("unknown reconciliation snapshot is required");
        }
        applySnapshot(validated);
        status = Status.RECONCILIATION_REQUIRED;
        nextOperation = Operation.QUERY;
        nextAttemptAt = now.plus(delay);
        updatedAt = now;
        clearLease();
    }

    public void requireRecovery(
            String owner,
            long token,
            Instant now,
            PaymentSnapshot snapshot
    ) {
        requireFence(owner, token, now);
        if (snapshot != null) {
            applySnapshot(snapshot);
        }
        status = Status.RECOVERY_REQUIRED;
        nextAttemptAt = null;
        completedAt = now;
        updatedAt = now;
        clearLease();
    }

    private void applySnapshot(PaymentSnapshot snapshot) {
        dispositionId = snapshot.dispositionId();
        refundId = snapshot.refundId();
        originalAmountMinor = snapshot.originalAmountMinor();
        targetRefundAmountMinor = snapshot.targetRefundAmountMinor();
        incrementalRefundAmountMinor = snapshot.incrementalRefundAmountMinor();
        completedRefundAmountMinor = snapshot.completedRefundAmountMinor();
        withheldAmountMinor = snapshot.withheldAmountMinor();
        currency = snapshot.currency();
        paymentDispositionStatus = snapshot.status();
        failureClassification = snapshot.failureClassification();
        paymentRequestedAt = snapshot.requestedAt();
        paymentUpdatedAt = snapshot.paymentUpdatedAt();
        paymentCompletedAt = snapshot.paymentCompletedAt();
    }

    private void requireFence(String owner, long token, Instant now) {
        if (!isOwnedBy(owner, token, now)) {
            throw new IllegalStateException("stale disposition obligation claim");
        }
    }

    private static void requireLease(String owner, Instant now, Instant until) {
        if (owner == null || owner.isBlank() || owner.length() > 64
                || now == null || until == null || !until.isAfter(now)) {
            throw new IllegalArgumentException("lease fields must be valid");
        }
    }

    private static void requireDelay(Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
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

    private static String requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain valid text");
        }
        return value;
    }

    private static String requirePattern(
            String value,
            Pattern pattern,
            String name
    ) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return value;
    }

    private static String requireResponsibility(String value) {
        if (!"CONSUMER".equals(value)
                && !"STORE_RESPONSIBLE".equals(value)
                && !"PLATFORM_RESPONSIBLE".equals(value)) {
            throw new IllegalArgumentException("responsibilityCode is not supported");
        }
        return value;
    }

    private static int requireTargetRate(int value) {
        if (value != 0 && value != 5_000 && value != 10_000) {
            throw new IllegalArgumentException(
                    "targetRefundRateBasisPoints must be 0, 5000, or 10000");
        }
        return value;
    }

    public record PaymentSnapshot(
            String dispositionId,
            String refundId,
            long originalAmountMinor,
            long targetRefundAmountMinor,
            long incrementalRefundAmountMinor,
            long completedRefundAmountMinor,
            long withheldAmountMinor,
            String currency,
            String status,
            String failureClassification,
            Instant requestedAt,
            Instant paymentUpdatedAt,
            Instant paymentCompletedAt
    ) {
        public PaymentSnapshot {
            requirePattern(dispositionId, UUID, "dispositionId");
            if (refundId != null) {
                requirePattern(refundId, PUBLIC_ID, "refundId");
            }
            if (originalAmountMinor <= 0
                    || targetRefundAmountMinor < 0
                    || targetRefundAmountMinor > originalAmountMinor
                    || incrementalRefundAmountMinor < 0
                    || incrementalRefundAmountMinor > originalAmountMinor
                    || completedRefundAmountMinor < 0
                    || completedRefundAmountMinor > originalAmountMinor
                    || withheldAmountMinor < 0
                    || withheldAmountMinor > originalAmountMinor) {
                throw new IllegalArgumentException("payment snapshot amounts are invalid");
            }
            requirePattern(currency, CURRENCY, "currency");
            requireText(status, 32, "status");
            if (failureClassification != null) {
                requireText(failureClassification, 16, "failureClassification");
            }
            Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            Objects.requireNonNull(paymentUpdatedAt, "paymentUpdatedAt must not be null");
        }
    }

    public Long getId() { return id; }
    public long getReservationDepositProcessId() { return reservationDepositProcessId; }
    public long getReservationId() { return reservationId; }
    public String getPaymentId() { return paymentId; }
    public String getSourceEventId() { return sourceEventId; }
    public String getSourceEventType() { return sourceEventType; }
    public String getCorrectsSourceEventId() { return correctsSourceEventId; }
    public long getPolicyVersion() { return policyVersion; }
    public String getResponsibilityCode() { return responsibilityCode; }
    public int getTargetRefundRateBasisPoints() { return targetRefundRateBasisPoints; }
    public String getObligationKey() { return obligationKey; }
    public String getCancellationIdempotencyKey() { return cancellationIdempotencyKey; }
    public Status getStatus() { return status; }
    public Operation getNextOperation() { return nextOperation; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public long getClaimToken() { return claimToken; }
    public Instant getLastAttemptedAt() { return lastAttemptedAt; }
    public String getDispositionId() { return dispositionId; }
    public String getRefundId() { return refundId; }
    public Long getOriginalAmountMinor() { return originalAmountMinor; }
    public Long getTargetRefundAmountMinor() { return targetRefundAmountMinor; }
    public Long getIncrementalRefundAmountMinor() { return incrementalRefundAmountMinor; }
    public Long getCompletedRefundAmountMinor() { return completedRefundAmountMinor; }
    public Long getWithheldAmountMinor() { return withheldAmountMinor; }
    public String getCurrency() { return currency; }
    public String getPaymentDispositionStatus() { return paymentDispositionStatus; }
    public String getFailureClassification() { return failureClassification; }
    public Instant getPaymentRequestedAt() { return paymentRequestedAt; }
    public Instant getPaymentUpdatedAt() { return paymentUpdatedAt; }
    public Instant getPaymentCompletedAt() { return paymentCompletedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
