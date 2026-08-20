package com.miriyum.domain.reservation.service;

/** Pure Reservation-owned monetary disposition decision for cancellation policy V2. */
public record ReservationDepositDispositionDecision(
        long policyVersion,
        Responsibility responsibility,
        int targetRefundRateBasisPoints
) {

    public enum Responsibility {
        CONSUMER,
        STORE_RESPONSIBLE,
        PLATFORM_RESPONSIBLE
    }

    public ReservationDepositDispositionDecision {
        if (policyVersion != 2L) {
            throw new IllegalArgumentException("policyVersion must be 2");
        }
        if (responsibility == null) {
            throw new IllegalArgumentException("responsibility must not be null");
        }
        if (targetRefundRateBasisPoints != 0
                && targetRefundRateBasisPoints != 5_000
                && targetRefundRateBasisPoints != 10_000) {
            throw new IllegalArgumentException(
                    "targetRefundRateBasisPoints must be 0, 5000, or 10000");
        }
    }
}
