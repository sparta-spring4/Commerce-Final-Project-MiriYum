package com.miriyum.domain.reservation.entity;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.Calculation;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;

/** Reservation-owned source of truth for deposit orchestration and unfinished obligations. */
@Entity
@Table(name = "reservation_deposit_processes")
public class ReservationDepositProcess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_deposit_process_id")
    private Long id;
    @Column(name = "reservation_hold_id", nullable = false, unique = true, updatable = false)
    private long reservationHoldId;
    @Column(name = "consumer_account_id", nullable = false, updatable = false)
    private long consumerAccountId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReservationDepositProcessStatus status;
    @Version
    @Column(name = "status_version", nullable = false)
    private long statusVersion;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(name = "payment_id", nullable = false, unique = true, updatable = false, length = 64)
    private String paymentId;
    @Column(name = "portone_payment_id", nullable = false, updatable = false, length = 100)
    private String portOnePaymentId;
    @Column(name = "payment_order_name", nullable = false, updatable = false, length = 100)
    private String paymentOrderName;
    @Column(name = "payment_amount_minor", nullable = false, updatable = false)
    private long paymentAmountMinor;
    @Column(name = "payment_currency", nullable = false, updatable = false, length = 3)
    private String paymentCurrency;
    @Column(name = "payment_source_expires_at", nullable = false, updatable = false)
    private Instant paymentSourceExpiresAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_preparation_status", nullable = false, updatable = false, length = 16)
    private PaymentStatus paymentPreparationStatus;
    @Embedded
    private ReservationDepositCalculationSnapshot calculationSnapshot;
    @Column(name = "abandonment_requested", nullable = false)
    private boolean abandonmentRequested;
    @Column(name = "abandonment_requested_at")
    private Instant abandonmentRequestedAt;
    @Column(name = "resources_protected", nullable = false)
    private boolean resourcesProtected;
    @Column(name = "resources_protected_at")
    private Instant resourcesProtectedAt;
    @Column(name = "final_reservation_id", unique = true)
    private Long finalReservationId;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "reconciliation_next_attempt_at")
    private Instant reconciliationNextAttemptAt;
    @Column(name = "reconciliation_lease_owner", length = 64)
    private String reconciliationLeaseOwner;
    @Column(name = "reconciliation_lease_until")
    private Instant reconciliationLeaseUntil;
    @Column(name = "reconciliation_claim_token", nullable = false)
    private long reconciliationClaimToken;
    @Column(name = "reconciliation_last_attempted_at")
    private Instant reconciliationLastAttemptedAt;

    protected ReservationDepositProcess() {
    }

    public static ReservationDepositProcess awaitingPayment(
            long reservationHoldId,
            long consumerAccountId,
            Instant expiresAt,
            Calculation calculation,
            PaymentPreparation payment,
            Instant requestedAt
    ) {
        if (reservationHoldId <= 0 || consumerAccountId <= 0) {
            throw new IllegalArgumentException("hold and consumer ids must be positive");
        }
        if (expiresAt == null || requestedAt == null || payment == null) {
            throw new IllegalArgumentException("times and payment are required");
        }
        if (payment.status() != PaymentStatus.READY
                || !expiresAt.equals(payment.sourceExpiresAt())
                || calculation.amountMinor() != payment.amountMinor()
                || !calculation.currency().equals(payment.currency())) {
            throw new IllegalArgumentException("payment preparation must match calculation and hold");
        }
        ReservationDepositProcess process = new ReservationDepositProcess();
        process.reservationHoldId = reservationHoldId;
        process.consumerAccountId = consumerAccountId;
        process.status = ReservationDepositProcessStatus.AWAITING_PAYMENT;
        process.expiresAt = expiresAt;
        process.paymentId = payment.paymentId();
        process.portOnePaymentId = payment.portOnePaymentId();
        process.paymentOrderName = payment.orderName();
        process.paymentAmountMinor = payment.amountMinor();
        process.paymentCurrency = payment.currency();
        process.paymentSourceExpiresAt = payment.sourceExpiresAt();
        process.paymentPreparationStatus = payment.status();
        process.calculationSnapshot = ReservationDepositCalculationSnapshot.copyOf(calculation);
        process.requestedAt = requestedAt;
        process.reconciliationNextAttemptAt = requestedAt;
        return process;
    }

    public long claimReconciliation(String owner, Instant now, Instant until) {
        requireLease(owner, now, until);
        if (status != ReservationDepositProcessStatus.AWAITING_PAYMENT
                && status != ReservationDepositProcessStatus.RECOVERY_REQUIRED) {
            throw invalidTransition();
        }
        boolean due = reconciliationNextAttemptAt != null
                && !reconciliationNextAttemptAt.isAfter(now);
        boolean reclaimable = reconciliationLeaseUntil != null
                && !reconciliationLeaseUntil.isAfter(now);
        boolean leaseAvailable = reconciliationLeaseUntil == null || reclaimable;
        if (!leaseAvailable || (!due && !reclaimable)) {
            throw new IllegalStateException("deposit process is not claimable");
        }
        reconciliationClaimToken = Math.addExact(reconciliationClaimToken, 1L);
        reconciliationLeaseOwner = owner;
        reconciliationLeaseUntil = until;
        reconciliationLastAttemptedAt = now;
        reconciliationNextAttemptAt = null;
        return reconciliationClaimToken;
    }

    public boolean isReconciliationClaimOwnedBy(
            String owner,
            long token,
            Instant now
    ) {
        return owner != null
                && now != null
                && token == reconciliationClaimToken
                && owner.equals(reconciliationLeaseOwner)
                && reconciliationLeaseUntil != null
                && reconciliationLeaseUntil.isAfter(now);
    }

    public void requeueReconciliation(
            String owner,
            long token,
            Instant now,
            Duration delay
    ) {
        requireReconciliationFence(owner, token, now);
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        reconciliationNextAttemptAt = now.plus(delay);
        clearReconciliationLease();
    }

    public void completeReconciliationClaim(String owner, long token, Instant now) {
        requireReconciliationFence(owner, token, now);
        reconciliationNextAttemptAt = null;
        clearReconciliationLease();
    }

    public void requestAbandonment(Instant requestedAt) {
        if (status == ReservationDepositProcessStatus.COMPLETED) {
            throw invalidTransition();
        }
        if (!abandonmentRequested) {
            abandonmentRequested = true;
            abandonmentRequestedAt = requireTime(requestedAt);
        }
    }

    public void beginFinalization(Instant requestedAt) {
        requireTime(requestedAt);
        if (status != ReservationDepositProcessStatus.AWAITING_PAYMENT
                || abandonmentRequested) {
            throw invalidTransition();
        }
        status = ReservationDepositProcessStatus.FINALIZING_RESOURCES;
    }

    public void protectResources(Instant protectedAt) {
        if (status != ReservationDepositProcessStatus.AWAITING_PAYMENT) {
            throw invalidTransition();
        }
        if (!resourcesProtected) {
            resourcesProtected = true;
            resourcesProtectedAt = requireTime(protectedAt);
        }
    }

    public void complete(long reservationId, Instant completedAt) {
        if (reservationId <= 0
                || status != ReservationDepositProcessStatus.FINALIZING_RESOURCES
                || abandonmentRequested) {
            throw invalidTransition();
        }
        finalReservationId = reservationId;
        this.completedAt = requireTime(completedAt);
        status = ReservationDepositProcessStatus.COMPLETED;
    }

    public void expire(Instant expiredAt) {
        Instant occurredAt = requireTime(expiredAt);
        if (status != ReservationDepositProcessStatus.AWAITING_PAYMENT
                || abandonmentRequested
                || occurredAt.isBefore(expiresAt)) {
            throw invalidTransition();
        }
        completedAt = occurredAt;
        status = ReservationDepositProcessStatus.EXPIRED;
    }

    public void abandon(Instant abandonedAt) {
        Instant occurredAt = requireTime(abandonedAt);
        if (status != ReservationDepositProcessStatus.AWAITING_PAYMENT
                || !abandonmentRequested) {
            throw invalidTransition();
        }
        completedAt = occurredAt;
        status = ReservationDepositProcessStatus.ABANDONED;
    }

    public void requireCompensation(Instant requiredAt) {
        requireTime(requiredAt);
        if (status != ReservationDepositProcessStatus.AWAITING_PAYMENT) {
            throw invalidTransition();
        }
        status = ReservationDepositProcessStatus.COMPENSATION_REQUIRED;
    }

    public void beginCompensation(Instant claimedAt) {
        requireTime(claimedAt);
        if (status != ReservationDepositProcessStatus.COMPENSATION_REQUIRED
                && status != ReservationDepositProcessStatus.COMPENSATING) {
            throw invalidTransition();
        }
        status = ReservationDepositProcessStatus.COMPENSATING;
    }

    public void completeCompensation(Instant compensatedAt) {
        Instant occurredAt = requireTime(compensatedAt);
        if (status != ReservationDepositProcessStatus.COMPENSATING) {
            throw invalidTransition();
        }
        completedAt = occurredAt;
        status = ReservationDepositProcessStatus.COMPENSATED;
    }

    public void requireRecovery(Instant requiredAt) {
        requireTime(requiredAt);
        if (status == ReservationDepositProcessStatus.COMPLETED
                || status == ReservationDepositProcessStatus.COMPENSATED) {
            throw invalidTransition();
        }
        status = ReservationDepositProcessStatus.RECOVERY_REQUIRED;
    }

    /** Stops payment polling when recovery is owned by a terminal refund reconciliation. */
    public void suspendReconciliation() {
        if (status != ReservationDepositProcessStatus.RECOVERY_REQUIRED) {
            throw invalidTransition();
        }
        reconciliationNextAttemptAt = null;
        clearReconciliationLease();
    }

    private static Instant requireTime(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("time is required");
        }
        return value;
    }

    private static void requireLease(String owner, Instant now, Instant until) {
        if (owner == null || owner.isBlank() || owner.length() > 64
                || now == null || until == null || !until.isAfter(now)) {
            throw new IllegalArgumentException("reconciliation lease fields must be valid");
        }
    }

    private void requireReconciliationFence(String owner, long token, Instant now) {
        if (!isReconciliationClaimOwnedBy(owner, token, now)) {
            throw new IllegalStateException("stale deposit process reconciliation claim");
        }
    }

    private void clearReconciliationLease() {
        reconciliationLeaseOwner = null;
        reconciliationLeaseUntil = null;
    }

    private static ServiceException invalidTransition() {
        return new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
    }

    public Long getId() { return id; }
    public long getReservationHoldId() { return reservationHoldId; }
    public long getConsumerAccountId() { return consumerAccountId; }
    public ReservationDepositProcessStatus getStatus() { return status; }
    public long getStatusVersion() { return statusVersion; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getPaymentId() { return paymentId; }
    public String getPortOnePaymentId() { return portOnePaymentId; }
    public String getPaymentOrderName() { return paymentOrderName; }
    public long getPaymentAmountMinor() { return paymentAmountMinor; }
    public String getPaymentCurrency() { return paymentCurrency; }
    public Instant getPaymentSourceExpiresAt() { return paymentSourceExpiresAt; }
    public PaymentStatus getPaymentPreparationStatus() { return paymentPreparationStatus; }
    public ReservationDepositCalculationSnapshot getCalculationSnapshot() {
        return calculationSnapshot;
    }
    public boolean isAbandonmentRequested() { return abandonmentRequested; }
    public Instant getAbandonmentRequestedAt() { return abandonmentRequestedAt; }
    public boolean isResourcesProtected() { return resourcesProtected; }
    public Instant getResourcesProtectedAt() { return resourcesProtectedAt; }
    public Long getFinalReservationId() { return finalReservationId; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getReconciliationNextAttemptAt() { return reconciliationNextAttemptAt; }
    public String getReconciliationLeaseOwner() { return reconciliationLeaseOwner; }
    public Instant getReconciliationLeaseUntil() { return reconciliationLeaseUntil; }
    public long getReconciliationClaimToken() { return reconciliationClaimToken; }
    public Instant getReconciliationLastAttemptedAt() {
        return reconciliationLastAttemptedAt;
    }
}
