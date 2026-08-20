package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.ApplyReservationDepositDispositionCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionFailureClassification;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.dto.PaymentContracts.GetReservationDepositDispositionQuery;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_DISPOSITION;
import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import com.miriyum.domain.reservation.repository.ReservationDepositDispositionObligationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short claim/result transactions for Reservation deposit disposition work. */
@Service
public class ReservationDepositDispositionService {

    private final ReservationDepositDispositionObligationRepository repository;
    private final ReservationPaymentRecoveryOutboxService recoveryOutbox;
    private final Clock clock;
    private final Duration leaseDuration;

    public ReservationDepositDispositionService(
            ReservationDepositDispositionObligationRepository repository,
            ReservationPaymentRecoveryOutboxService recoveryOutbox,
            Clock clock,
            @Qualifier("reservationDepositDispositionLeaseDuration")
            Duration leaseDuration
    ) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.repository = repository;
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
        return repository.findClaimableForUpdate(now, PageRequest.of(0, limit))
                .stream()
                .map(obligation -> {
                    obligation.claim(owner, now, now.plus(leaseDuration));
                    repository.saveAndFlush(obligation);
                    return Claim.from(obligation, owner);
                })
                .toList();
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordResult(
            Claim claim,
            DispositionResult result,
            Duration retryDelay,
            Duration queryDelay,
            int maxAttempts
    ) {
        requireResultArguments(claim, result, retryDelay, queryDelay, maxAttempts);
        Instant now = clock.instant();
        ReservationDepositDispositionObligation obligation = current(claim, now);
        if (obligation == null) {
            return false;
        }
        ReservationDepositDispositionObligation.PaymentSnapshot snapshot =
                snapshot(claim, result);
        boolean completed = false;
        switch (result.status()) {
            case COMPLETED -> {
                obligation.complete(claim.owner(), claim.token(), now, snapshot);
                completed = true;
            }
            case PROCESSING -> {
                obligation.scheduleQuery(
                        claim.owner(), claim.token(), now, queryDelay, snapshot);
            }
            case RECONCILIATION_REQUIRED -> {
                if (claim.attemptCount() >= maxAttempts) {
                    obligation.requireRecovery(claim.owner(), claim.token(), now, snapshot);
                } else {
                    obligation.requireReconciliation(
                            claim.owner(), claim.token(), now, queryDelay, snapshot);
                }
            }
            case FAILED -> {
                if (result.failureClassification()
                        == DispositionFailureClassification.RETRYABLE
                        && claim.attemptCount() < maxAttempts) {
                    if (claim.operation()
                            == ReservationDepositDispositionObligation.Operation.QUERY) {
                        obligation.requeueQuery(
                                claim.owner(),
                                claim.token(),
                                now,
                                queryDelay,
                                snapshot);
                    } else {
                        obligation.requeue(
                                claim.owner(), claim.token(), now, retryDelay, snapshot);
                    }
                } else {
                    obligation.requireRecovery(
                            claim.owner(), claim.token(), now, snapshot);
                }
            }
        }
        if (obligation.getStatus()
                == ReservationDepositDispositionObligation.Status.RECOVERY_REQUIRED) {
            enqueueRecovery(obligation);
        }
        repository.saveAndFlush(obligation);
        return completed;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordRetryableFailure(
            Claim claim,
            Duration delay,
            int maxAttempts
    ) {
        if (claim == null || delay == null || delay.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("claim, delay, and maxAttempts are invalid");
        }
        Instant now = clock.instant();
        ReservationDepositDispositionObligation obligation = current(claim, now);
        if (obligation == null) {
            return false;
        }
        if (claim.attemptCount() >= maxAttempts) {
            obligation.requireRecovery(claim.owner(), claim.token(), now, null);
            enqueueRecovery(obligation);
        } else if (claim.operation()
                == ReservationDepositDispositionObligation.Operation.QUERY) {
            obligation.requeueQuery(
                    claim.owner(), claim.token(), now, delay, null);
        } else {
            obligation.requeue(claim.owner(), claim.token(), now, delay);
        }
        repository.saveAndFlush(obligation);
        return true;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordRecoveryRequired(Claim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim must not be null");
        }
        Instant now = clock.instant();
        ReservationDepositDispositionObligation obligation = current(claim, now);
        if (obligation == null) {
            return false;
        }
        obligation.requireRecovery(claim.owner(), claim.token(), now, null);
        enqueueRecovery(obligation);
        repository.saveAndFlush(obligation);
        return true;
    }

    private void enqueueRecovery(ReservationDepositDispositionObligation obligation) {
        recoveryOutbox.enqueue(
                RESERVATION_DEPOSIT_DISPOSITION,
                Long.toString(obligation.getId()),
                obligation.getPaymentId(),
                obligation.getSourceEventId(),
                obligation.getObligationKey());
    }

    private ReservationDepositDispositionObligation current(Claim claim, Instant now) {
        ReservationDepositDispositionObligation obligation = repository
                .findByIdForUpdate(claim.obligationId())
                .orElse(null);
        if (obligation == null
                || !matches(obligation, claim)
                || !obligation.isOwnedBy(claim.owner(), claim.token(), now)) {
            return null;
        }
        return obligation;
    }

    private static boolean matches(
            ReservationDepositDispositionObligation obligation,
            Claim claim
    ) {
        return obligation.getReservationDepositProcessId() == claim.processId()
                && obligation.getReservationId() == claim.reservationId()
                && obligation.getPolicyVersion() == claim.policyVersion()
                && obligation.getTargetRefundRateBasisPoints()
                    == claim.targetRefundRateBasisPoints()
                && obligation.getPaymentId().equals(claim.paymentId())
                && obligation.getSourceEventId().equals(claim.sourceEventId())
                && obligation.getSourceEventType().equals(claim.sourceEventType())
                && java.util.Objects.equals(
                        obligation.getCorrectsSourceEventId(), claim.correctsSourceEventId())
                && obligation.getResponsibilityCode().equals(claim.responsibilityCode())
                && obligation.getObligationKey().equals(claim.obligationKey());
    }

    private static ReservationDepositDispositionObligation.PaymentSnapshot snapshot(
            Claim claim,
            DispositionResult result
    ) {
        try {
            if (!claim.obligationKey().equals(result.dispositionId())
                    || !claim.paymentId().equals(result.paymentId())
                    || !claim.sourceEventId().equals(result.sourceEventId())
                    || !claim.sourceEventType().equals(result.sourceEventType())
                    || !java.util.Objects.equals(
                            claim.correctsSourceEventId(), result.correctsSourceEventId())
                    || claim.policyVersion() != result.policyVersion()
                    || !claim.responsibilityCode().equals(result.responsibilityCode())
                    || claim.targetRefundRateBasisPoints()
                        != result.targetRefundRateBasisPoints()) {
                throw new IllegalStateException(
                        "payment disposition result identity mismatch");
            }
            return new ReservationDepositDispositionObligation.PaymentSnapshot(
                    result.dispositionId(),
                    result.refundId(),
                    result.originalAmountMinor(),
                    result.targetRefundAmountMinor(),
                    result.incrementalRefundAmountMinor(),
                    result.completedRefundAmountMinor(),
                    result.withheldAmountMinor(),
                    result.currency(),
                    result.status().name(),
                    result.failureClassification() == null
                            ? null
                            : result.failureClassification().name(),
                    result.requestedAt(),
                    result.updatedAt(),
                    result.completedAt());
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException(
                    "payment disposition result is malformed", failure);
        }
    }

    private static void requireResultArguments(
            Claim claim,
            DispositionResult result,
            Duration retryDelay,
            Duration queryDelay,
            int maxAttempts
    ) {
        if (claim == null || result == null
                || retryDelay == null || retryDelay.isNegative()
                || queryDelay == null || queryDelay.isNegative()
                || maxAttempts < 1) {
            throw new IllegalArgumentException("result recording arguments are invalid");
        }
    }

    public record Claim(
            long obligationId,
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
            ReservationDepositDispositionObligation.Operation operation,
            String owner,
            long token,
            int attemptCount
    ) {
        private static Claim from(
                ReservationDepositDispositionObligation obligation,
                String owner
        ) {
            if (obligation.getId() == null || obligation.getId() <= 0) {
                throw new IllegalStateException("persisted disposition obligation id is required");
            }
            return new Claim(
                    obligation.getId(),
                    obligation.getReservationDepositProcessId(),
                    obligation.getReservationId(),
                    obligation.getPaymentId(),
                    obligation.getSourceEventId(),
                    obligation.getSourceEventType(),
                    obligation.getCorrectsSourceEventId(),
                    obligation.getPolicyVersion(),
                    obligation.getResponsibilityCode(),
                    obligation.getTargetRefundRateBasisPoints(),
                    obligation.getObligationKey(),
                    obligation.getNextOperation(),
                    owner,
                    obligation.getClaimToken(),
                    obligation.getAttemptCount());
        }

        public ApplyReservationDepositDispositionCommand toApplyCommand() {
            return new ApplyReservationDepositDispositionCommand(
                    paymentId,
                    sourceEventId,
                    sourceEventType,
                    correctsSourceEventId,
                    policyVersion,
                    responsibilityCode,
                    targetRefundRateBasisPoints,
                    obligationKey);
        }

        public GetReservationDepositDispositionQuery toQuery() {
            return new GetReservationDepositDispositionQuery(paymentId, sourceEventId);
        }
    }
}
