package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositRefundObligationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional lease/fencing boundary for deposit refund work. */
@Service
public class ReservationDepositRefundService {

    private final ReservationDepositRefundObligationRepository refundRepository;
    private final ReservationDepositProcessRepository processRepository;
    private final ReservationPaymentRecoveryOutboxService recoveryOutbox;
    private final Clock clock;
    private final Duration leaseDuration;

    public ReservationDepositRefundService(
            ReservationDepositRefundObligationRepository refundRepository,
            ReservationDepositProcessRepository processRepository,
            ReservationPaymentRecoveryOutboxService recoveryOutbox,
            Clock clock,
            @Qualifier("reservationDepositRefundLeaseDuration")
            Duration leaseDuration
    ) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.refundRepository = refundRepository;
        this.processRepository = processRepository;
        this.recoveryOutbox = recoveryOutbox;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public List<Claim> claimDue(String owner, int limit) {
        if (owner == null || owner.isBlank() || owner.length() > 64) {
            throw new IllegalArgumentException("owner must be 1 to 64 characters");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Instant now = clock.instant();
        List<ReservationDepositRefundObligation> obligations = refundRepository
                .findClaimableForUpdate(now, PageRequest.of(0, limit));
        return obligations.stream().map(obligation -> {
            obligation.claim(owner, now, now.plus(leaseDuration));
            ReservationDepositProcess process = processRepository
                    .findByIdForUpdate(obligation.getReservationDepositProcessId())
                    .orElseThrow(() -> new IllegalStateException(
                            "refund obligation process is missing"));
            process.beginCompensation(now);
            refundRepository.save(obligation);
            processRepository.saveAndFlush(process);
            return Claim.from(obligation, owner);
        }).toList();
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordCompleted(Claim claim, RefundResult refund) {
        if (claim == null || refund == null) {
            throw new IllegalArgumentException("claim and refund are required");
        }
        Instant now = clock.instant();
        ReservationDepositRefundObligation obligation = refundRepository
                .findByIdForUpdate(claim.obligationId())
                .orElse(null);
        if (obligation == null
                || !obligation.matchesRequired(
                        claim.processId(),
                        claim.paymentId(),
                        claim.refundAmountMinor(),
                        claim.currency(),
                        claim.refundPolicyVersion(),
                        claim.sourceEventId(),
                        claim.idempotencyKey(),
                        claim.reasonCode())
                || !obligation.isOwnedBy(claim.owner(), claim.token(), now)) {
            return false;
        }
        if (refund.status() != RefundStatus.COMPLETED
                || !claim.paymentId().equals(refund.paymentId())
                || claim.refundAmountMinor() != refund.requestedAmountMinor()
                || claim.refundAmountMinor() != refund.completedAmountMinor()
                || !claim.currency().equals(refund.currency())) {
            throw new IllegalStateException("completed refund does not satisfy obligation");
        }
        ReservationDepositProcess process = processRepository
                .findByIdForUpdate(claim.processId())
                .orElseThrow(() -> new IllegalStateException(
                        "refund obligation process is missing"));
        obligation.complete(claim.owner(), claim.token(), now);
        process.completeCompensation(now);
        refundRepository.save(obligation);
        processRepository.saveAndFlush(process);
        return true;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordRetryableFailure(Claim claim, Duration delay) {
        if (claim == null || delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("claim and non-negative delay are required");
        }
        Instant now = clock.instant();
        ReservationDepositRefundObligation obligation = refundRepository
                .findByIdForUpdate(claim.obligationId())
                .orElse(null);
        if (obligation == null
                || !obligation.matchesRequired(
                        claim.processId(),
                        claim.paymentId(),
                        claim.refundAmountMinor(),
                        claim.currency(),
                        claim.refundPolicyVersion(),
                        claim.sourceEventId(),
                        claim.idempotencyKey(),
                        claim.reasonCode())
                || !obligation.isOwnedBy(claim.owner(), claim.token(), now)) {
            return false;
        }
        obligation.requeue(claim.owner(), claim.token(), now, delay);
        refundRepository.save(obligation);
        return true;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordRecoveryRequired(Claim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim is required");
        }
        return isolateForRecovery(claim);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordReconciliationRequired(Claim claim, RefundResult refund) {
        if (claim == null || refund == null) {
            throw new IllegalArgumentException("claim and refund are required");
        }
        if ((refund.status() != RefundStatus.FAILED
                && refund.status() != RefundStatus.RECONCILIATION_REQUIRED)
                || !claim.paymentId().equals(refund.paymentId())
                || claim.refundAmountMinor() != refund.requestedAmountMinor()
                || !claim.currency().equals(refund.currency())) {
            throw new IllegalStateException(
                    "recovery-required refund does not match obligation");
        }
        return isolateForRecovery(claim);
    }

    private boolean isolateForRecovery(Claim claim) {
        Instant now = clock.instant();
        ReservationDepositRefundObligation obligation = refundRepository
                .findByIdForUpdate(claim.obligationId())
                .orElse(null);
        if (obligation == null
                || !obligation.matchesRequired(
                        claim.processId(),
                        claim.paymentId(),
                        claim.refundAmountMinor(),
                        claim.currency(),
                        claim.refundPolicyVersion(),
                        claim.sourceEventId(),
                        claim.idempotencyKey(),
                        claim.reasonCode())
                || !obligation.isOwnedBy(claim.owner(), claim.token(), now)) {
            return false;
        }
        ReservationDepositProcess process = processRepository
                .findByIdForUpdate(claim.processId())
                .orElseThrow(() -> new IllegalStateException(
                        "refund obligation process is missing"));
        obligation.requireReconciliation(claim.owner(), claim.token(), now);
        process.requireRecovery(now);
        process.suspendReconciliation();
        recoveryOutbox.enqueue(
                RESERVATION_DEPOSIT_REFUND,
                Long.toString(claim.obligationId()),
                claim.paymentId(),
                claim.sourceEventId(),
                claim.idempotencyKey());
        refundRepository.save(obligation);
        processRepository.saveAndFlush(process);
        return true;
    }

    public record Claim(
            long obligationId,
            long processId,
            String paymentId,
            long refundAmountMinor,
            String currency,
            long refundPolicyVersion,
            String sourceEventId,
            String idempotencyKey,
            String reasonCode,
            String owner,
            long token
    ) {
        private static Claim from(
                ReservationDepositRefundObligation obligation,
                String owner
        ) {
            if (obligation.getId() == null || obligation.getId() <= 0) {
                throw new IllegalStateException("persisted refund obligation id is required");
            }
            return new Claim(
                    obligation.getId(),
                    obligation.getReservationDepositProcessId(),
                    obligation.getPaymentId(),
                    obligation.getRefundAmountMinor(),
                    obligation.getCurrency(),
                    obligation.getRefundPolicyVersion(),
                    obligation.getSourceEventId(),
                    obligation.getIdempotencyKey(),
                    obligation.getReasonCode(),
                    owner,
                    obligation.getClaimToken());
        }
    }
}
