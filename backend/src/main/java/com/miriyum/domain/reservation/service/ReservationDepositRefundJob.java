package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.service.PaymentService;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Executes Payment refund calls outside Reservation claim/result transactions. */
@Component
public class ReservationDepositRefundJob {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final ReservationDepositRefundService refundService;
    private final PaymentService paymentService;

    public ReservationDepositRefundJob(
            ReservationDepositRefundService refundService,
            PaymentService paymentService
    ) {
        this.refundService = refundService;
        this.paymentService = paymentService;
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
            if (refund.status() == RefundStatus.RECONCILIATION_REQUIRED) {
                refundService.recordReconciliationRequired(claim, refund);
                continue;
            }
            if (refund.status() == RefundStatus.FAILED) {
                refundService.recordRetryableFailure(claim, RETRY_DELAY);
                continue;
            }
            if (refundService.recordCompleted(claim, refund)) {
                completed++;
            }
        }
        return completed;
    }
}
