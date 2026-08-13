package com.miriyum.domain.reservation.port.dto;

/** 임시 MenuHold의 존재·상태·최종 Reservation 연결만 공개하는 포트 결과다. */
public record ReservationTemporaryMenuHoldResult(
        Presence presence,
        State state,
        Long finalReservationId
) {
    public ReservationTemporaryMenuHoldResult {
        if (presence == null) {
            throw new IllegalArgumentException("presence must not be null");
        }
        if (presence == Presence.NO_HOLD) {
            if (state != null || finalReservationId != null) {
                throw new IllegalArgumentException(
                        "NO_HOLD must not expose state or finalReservationId");
            }
        } else if (state == null) {
            throw new IllegalArgumentException("HOLD_PRESENT requires state");
        }
        if (state == State.CONFIRMED || state == State.FULFILLED) {
            if (finalReservationId == null || finalReservationId <= 0) {
                throw new IllegalArgumentException(
                        state + " requires a positive finalReservationId");
            }
        } else if (state == State.RELEASED) {
            if (finalReservationId != null && finalReservationId <= 0) {
                throw new IllegalArgumentException(
                        "RELEASED finalReservationId must be positive when present");
            }
        } else if (finalReservationId != null) {
            throw new IllegalArgumentException(
                    "finalReservationId is allowed only for final-linked terminal states");
        }
    }

    /** 임시 MenuHold 루트 행의 존재 의미다. */
    public enum Presence {
        NO_HOLD,
        HOLD_PRESENT
    }

    /** Reservation에 공개하는 임시 MenuHold 상태 의미다. */
    public enum State {
        ACTIVE,
        RECONCILIATION_REQUIRED,
        CONFIRMED,
        RELEASED,
        EXPIRED,
        FULFILLED
    }
}
