package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.waiting.entity.WaitingConversionCompensation;
import com.miriyum.domain.reservation.waiting.entity.WaitingConversionCompensationStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingConversionCompensationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WaitingConversionCompensationService {
    static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final WaitingConversionCompensationRepository repository;
    private final PaymentService paymentService;
    private final Clock clock;
    private final TransactionTemplate resultTransaction;

    public WaitingConversionCompensationService(
            WaitingConversionCompensationRepository repository,
            PaymentService paymentService,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.paymentService = paymentService;
        this.clock = clock;
        this.resultTransaction = new TransactionTemplate(transactionManager);
        this.resultTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.resultTransaction.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.resultTransaction.setTimeout(5);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public long recordRequired(
            long waitingTeamId,
            String paymentId,
            long refundAmountMinor,
            String currency,
            long refundPolicyVersion,
            String sourceEventId,
            String idempotencyKey,
            String reasonCode
    ) {
        Instant now = clock.instant();
        WaitingConversionCompensation required = WaitingConversionCompensation.pending(
                waitingTeamId,
                paymentId,
                refundAmountMinor,
                currency,
                refundPolicyVersion,
                sourceEventId,
                idempotencyKey,
                reasonCode,
                now);
        repository.insertRequired(
                waitingTeamId,
                paymentId,
                refundAmountMinor,
                currency,
                refundPolicyVersion,
                sourceEventId,
                idempotencyKey,
                reasonCode,
                now);
        WaitingConversionCompensation stored = repository
                .findByWaitingTeamIdAndPaymentIdForUpdate(waitingTeamId, paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "compensation source conflicts with another waiting team/payment"));
        if (!stored.matchesRequired(
                waitingTeamId,
                paymentId,
                refundAmountMinor,
                currency,
                refundPolicyVersion,
                sourceEventId,
                idempotencyKey,
                reasonCode)) {
            throw new IllegalStateException("conflicting waiting compensation payload");
        }
        return stored.getId();
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public List<WaitingCompensationClaim> claimPending(
            String owner,
            int limit,
            Duration leaseDuration,
            long afterId
    ) {
        if (limit < 1 || leaseDuration == null || leaseDuration.isZero()
                || leaseDuration.isNegative() || afterId < 0) {
            throw new IllegalArgumentException("claim arguments must be valid");
        }
        Instant now = clock.instant();
        List<WaitingConversionCompensation> claimable = repository.findClaimableForUpdate(
                WaitingConversionCompensationStatus.PENDING.name(),
                WaitingConversionCompensationStatus.PROCESSING.name(),
                now,
                afterId,
                Math.min(limit, 100));
        List<WaitingCompensationClaim> claims = new ArrayList<>();
        for (WaitingConversionCompensation compensation : claimable) {
            compensation.claim(owner, now, now.plus(leaseDuration));
            if (compensation.getAttemptCount() > MAX_ATTEMPTS) {
                compensation.requireReconciliation(
                        owner, compensation.getClaimToken(), now);
                continue;
            }
            claims.add(WaitingCompensationClaim.from(compensation, owner));
        }
        return List.copyOf(claims);
    }

    public boolean processClaim(WaitingCompensationClaim claim) {
        if (!inResultTransaction(() -> isCurrent(claim))) {
            return false;
        }

        RefundResult result = paymentService.requestRefund(claim.toRefundCommand());
        return inResultTransaction(() -> applyResult(claim, result));
    }

    public boolean recordFailure(WaitingCompensationClaim claim, boolean retryable) {
        return inResultTransaction(() -> {
            Instant now = clock.instant();
            WaitingConversionCompensation compensation = repository
                    .findByIdForUpdate(claim.compensationId())
                    .orElseThrow(() -> new IllegalStateException("compensation not found"));
            if (!compensation.isOwnedBy(claim.owner(), claim.token(), now)) {
                return false;
            }
            if (retryable && compensation.getAttemptCount() < MAX_ATTEMPTS) {
                compensation.requeue(claim.owner(), claim.token(), now, RETRY_DELAY);
            } else {
                compensation.requireReconciliation(claim.owner(), claim.token(), now);
            }
            return true;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public long countReconciliationRequired() {
        return repository.countByStatus(
                WaitingConversionCompensationStatus.RECONCILIATION_REQUIRED);
    }

    private boolean isCurrent(WaitingCompensationClaim claim) {
        WaitingConversionCompensation compensation = repository
                .findByIdForUpdate(claim.compensationId())
                .orElseThrow(() -> new IllegalStateException("compensation not found"));
        return compensation.isOwnedBy(
                claim.owner(), claim.token(), clock.instant());
    }

    private boolean applyResult(WaitingCompensationClaim claim, RefundResult result) {
        Instant now = clock.instant();
        WaitingConversionCompensation compensation = repository
                .findByIdForUpdate(claim.compensationId())
                .orElseThrow(() -> new IllegalStateException("compensation not found"));
        if (!compensation.isOwnedBy(claim.owner(), claim.token(), now)) {
            return false;
        }
        boolean matchingResult = result != null
                && claim.paymentId().equals(result.paymentId())
                && claim.refundAmountMinor() == result.requestedAmountMinor()
                && claim.currency().equals(result.currency());
        if (matchingResult && result.status() == RefundStatus.COMPLETED) {
            compensation.complete(claim.owner(), claim.token(), now);
        } else if (matchingResult && result.status() == RefundStatus.FAILED
                && compensation.getAttemptCount() < MAX_ATTEMPTS) {
            compensation.requeue(claim.owner(), claim.token(), now, RETRY_DELAY);
        } else {
            compensation.requireReconciliation(claim.owner(), claim.token(), now);
        }
        return true;
    }

    private boolean inResultTransaction(java.util.function.BooleanSupplier action) {
        Boolean result = resultTransaction.execute(status -> action.getAsBoolean());
        return Boolean.TRUE.equals(result);
    }
}
