package com.miriyum.domain.reservation.port.dto;

/** 메뉴 홀드 포트 명령 결과다. */
public record ReservationMenuHoldResult(long reservationId, Outcome outcome) {

    public enum Outcome {
        NO_HOLD,
        CONFIRMED,
        RELEASED,
        FULFILLED
    }

    public ReservationMenuHoldResult {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("reservationId must be positive");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
    }

    public static ReservationMenuHoldResult noHold(long reservationId) {
        return new ReservationMenuHoldResult(reservationId, Outcome.NO_HOLD);
    }

    public static ReservationMenuHoldResult confirmed(long reservationId) {
        return new ReservationMenuHoldResult(reservationId, Outcome.CONFIRMED);
    }

    public static ReservationMenuHoldResult released(long reservationId) {
        return new ReservationMenuHoldResult(reservationId, Outcome.RELEASED);
    }

    public static ReservationMenuHoldResult fulfilled(long reservationId) {
        return new ReservationMenuHoldResult(reservationId, Outcome.FULFILLED);
    }
}
