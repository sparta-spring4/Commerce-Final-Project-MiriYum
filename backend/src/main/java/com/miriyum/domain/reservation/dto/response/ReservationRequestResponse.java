package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Latest Reservation-owned view of a deposit-backed reservation request. */
public record ReservationRequestResponse(
        String reservationRequestId,
        ReservationDepositProcessStatus status,
        OffsetDateTime expiresAt,
        PaymentPreparationSnapshot paymentPreparation,
        boolean abandonmentRequested,
        ReservationDetailResponse reservation
) {
    public ReservationRequestResponse {
        requireText(reservationRequestId, "reservationRequestId");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(paymentPreparation, "paymentPreparation must not be null");
        if ((status == ReservationDepositProcessStatus.COMPLETED) != (reservation != null)) {
            throw new IllegalArgumentException(
                    "reservation must be present exactly when status is COMPLETED");
        }
    }

    /** Immutable snapshot copied from the initial Payment preparation. */
    public record PaymentPreparationSnapshot(
            String paymentId,
            String portOnePaymentId,
            String orderName,
            long amountMinor,
            String currency,
            OffsetDateTime sourceExpiresAt,
            String status
    ) {
        public PaymentPreparationSnapshot {
            requireText(paymentId, "paymentId");
            requireText(portOnePaymentId, "portOnePaymentId");
            requireText(orderName, "orderName");
            if (amountMinor <= 0) {
                throw new IllegalArgumentException("amountMinor must be positive");
            }
            if (!"KRW".equals(currency)) {
                throw new IllegalArgumentException("currency must be KRW");
            }
            Objects.requireNonNull(sourceExpiresAt, "sourceExpiresAt must not be null");
            if (!"READY".equals(status)) {
                throw new IllegalArgumentException("status must be READY");
            }
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
