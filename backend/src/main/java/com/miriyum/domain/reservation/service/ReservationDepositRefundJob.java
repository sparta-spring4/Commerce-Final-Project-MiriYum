package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.service.PaymentService;
import org.springframework.stereotype.Component;

/** Executes Payment refund calls outside Reservation claim/result transactions. */
@Component
public class ReservationDepositRefundJob {

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
            RefundResult refund = paymentService.requestRefund(
                    new RequestRefundCommand(
                            claim.paymentId(),
                            claim.sourceEventId(),
                            claim.refundAmountMinor(),
                            claim.reasonCode(),
                            claim.refundPolicyVersion(),
                            claim.idempotencyKey()));
            if (refundService.recordCompleted(claim, refund)) {
                completed++;
            }
        }
        return completed;
    }
}
