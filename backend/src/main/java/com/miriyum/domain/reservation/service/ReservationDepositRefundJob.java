package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.service.PaymentService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Executes Payment refund calls outside Reservation claim/result transactions. */
@Component
public class ReservationDepositRefundJob {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final ReservationDepositRefundService refundService;
    private final PaymentService paymentService;
    private final String owner;
    private final int batchSize;

    @Autowired
    public ReservationDepositRefundJob(
            ReservationDepositRefundService refundService,
            PaymentService paymentService,
            @Qualifier("reservationDepositRefundBatchSize") Integer batchSize
    ) {
        this(
                refundService,
                paymentService,
                "reservation-deposit-refund-" + UUID.randomUUID(),
                requireBatchSize(batchSize));
    }

    ReservationDepositRefundJob(
            ReservationDepositRefundService refundService,
            PaymentService paymentService
    ) {
        this(refundService, paymentService,
                "reservation-deposit-refund-" + UUID.randomUUID(), 100);
    }

    ReservationDepositRefundJob(
            ReservationDepositRefundService refundService,
            PaymentService paymentService,
            String owner,
            int batchSize
    ) {
        this.refundService = refundService;
        this.paymentService = paymentService;
        if (owner == null || owner.isBlank() || owner.length() > 64) {
            throw new IllegalArgumentException("owner must be 1 to 64 characters");
        }
        this.owner = owner;
        this.batchSize = requireBatchSize(batchSize);
    }

    public int runScheduled() {
        return runOnce(owner, batchSize);
    }

    public int runOnce(String owner, int limit) {
        int completed = 0;
        for (ReservationDepositRefundService.Claim claim
                : refundService.claimDue(owner, limit)) {
            RefundResult refund;
            try {
                refund = paymentService.requestRefund(
                        new RequestRefundCommand(
                                claim.paymentId(),
                                claim.sourceEventId(),
                                claim.refundAmountMinor(),
                                claim.reasonCode(),
                                claim.refundPolicyVersion(),
                                claim.idempotencyKey()));
            } catch (RuntimeException failure) {
                refundService.recordRetryableFailure(claim, RETRY_DELAY);
                continue;
            }
            switch (refund.status()) {
                case REQUESTED, VALIDATING, PROCESSING, FAILED ->
                        refundService.recordRetryableFailure(claim, RETRY_DELAY);
                case RECONCILIATION_REQUIRED ->
                        refundService.recordReconciliationRequired(claim, refund);
                case COMPLETED -> {
                    if (refundService.recordCompleted(claim, refund)) {
                        completed++;
                    }
                }
            }
        }
        return completed;
    }

    private static int requireBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        return batchSize;
    }
}
