package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import java.time.Instant;
import java.util.List;

/** 매장 운영자에게 공개하는 예약별 저장 결제·환불 상태다. */
public record StoreReservationPaymentStatusResponse(
        String reservationId,
        StorePaymentResult result,
        boolean reconciliationRequired,
        Instant observedAt,
        StoreReservationPayment payment
) {

    public enum StorePaymentResult {
        NOT_APPLICABLE,
        AWAITING_PAYMENT,
        PROCESSING,
        FAILED,
        COMPLETED,
        UNKNOWN
    }

    public record StoreReservationPayment(
            String paymentId,
            long amountMinor,
            long refundedAmountMinor,
            long refundableAmountMinor,
            String currency,
            PaymentStatus status,
            PaymentAttemptStatus lastAttemptStatus,
            Instant createdAt,
            Instant paidAt,
            Instant updatedAt,
            List<StoreReservationRefund> refunds
    ) {
        public StoreReservationPayment {
            refunds = List.copyOf(refunds);
        }
    }

    public record StoreReservationRefund(
            String refundId,
            long amountMinor,
            RefundStatus status,
            Instant requestedAt,
            Instant completedAt
    ) {
    }
}
