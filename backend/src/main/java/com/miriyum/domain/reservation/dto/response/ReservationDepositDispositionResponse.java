package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Latest Reservation-owned projection of a deposit disposition obligation. */
public record ReservationDepositDispositionResponse(
        long policyVersion,
        String responsibilityCode,
        int targetRefundRateBasisPoints,
        Long originalAmountMinor,
        Long targetRefundAmountMinor,
        Long completedRefundAmountMinor,
        Long withheldAmountMinor,
        String currency,
        String dispositionId,
        String refundId,
        String status,
        String paymentDispositionStatus,
        String failureClassification,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt,
        OffsetDateTime paymentRequestedAt,
        OffsetDateTime paymentUpdatedAt
) {

    public static ReservationDepositDispositionResponse from(
            ReservationDepositDispositionObligation obligation
    ) {
        if (obligation == null) {
            throw new IllegalArgumentException("obligation must not be null");
        }
        return new ReservationDepositDispositionResponse(
                obligation.getPolicyVersion(),
                obligation.getResponsibilityCode(),
                obligation.getTargetRefundRateBasisPoints(),
                obligation.getOriginalAmountMinor(),
                obligation.getTargetRefundAmountMinor(),
                obligation.getCompletedRefundAmountMinor(),
                obligation.getWithheldAmountMinor(),
                obligation.getCurrency(),
                obligation.getDispositionId(),
                obligation.getRefundId(),
                obligation.getStatus().name(),
                obligation.getPaymentDispositionStatus(),
                obligation.getFailureClassification(),
                utc(obligation.getCreatedAt()),
                utc(obligation.getUpdatedAt()),
                utc(obligation.getCompletedAt()),
                utc(obligation.getPaymentRequestedAt()),
                utc(obligation.getPaymentUpdatedAt())
        );
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
