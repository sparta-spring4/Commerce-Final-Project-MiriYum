package com.miriyum.domain.reservation.entity;

/** Public Reservation-owned orchestration stage; it does not duplicate Payment or Hold state. */
public enum ReservationDepositProcessStatus {
    AWAITING_PAYMENT,
    FINALIZING_RESOURCES,
    COMPLETED,
    ABANDONED,
    EXPIRED,
    COMPENSATION_REQUIRED,
    COMPENSATING,
    COMPENSATED,
    RECOVERY_REQUIRED
}
