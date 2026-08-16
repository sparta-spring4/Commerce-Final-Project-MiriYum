package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.reservation.waiting.entity.WaitingConversionCompensation;

record WaitingCompensationClaim(
        long compensationId,
        String owner,
        long token,
        String paymentId,
        long refundAmountMinor,
        String currency,
        long refundPolicyVersion,
        String sourceEventId,
        String idempotencyKey,
        String reasonCode
) {
    static WaitingCompensationClaim from(
            WaitingConversionCompensation compensation,
            String owner
    ) {
        if (compensation.getId() == null) {
            throw new IllegalStateException("compensation must be persisted before claiming");
        }
        return new WaitingCompensationClaim(
                compensation.getId(),
                owner,
                compensation.getClaimToken(),
                compensation.getPaymentId(),
                compensation.getRefundAmountMinor(),
                compensation.getCurrency(),
                compensation.getRefundPolicyVersion(),
                compensation.getSourceEventId(),
                compensation.getIdempotencyKey(),
                compensation.getReasonCode());
    }

    RequestRefundCommand toRefundCommand() {
        return new RequestRefundCommand(
                paymentId,
                sourceEventId,
                refundAmountMinor,
                reasonCode,
                refundPolicyVersion,
                idempotencyKey);
    }
}
