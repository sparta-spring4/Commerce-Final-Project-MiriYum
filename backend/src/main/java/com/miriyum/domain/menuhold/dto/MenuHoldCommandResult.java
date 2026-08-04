package com.miriyum.domain.menuhold.dto;

/** 예약 조정자가 저장하고 재생할 수 있는 메뉴 홀드 명령 결과다. */
public record MenuHoldCommandResult(
        long reservationId,
        Outcome outcome
) {

    public enum Outcome {
        NO_HOLD,
        CONFIRMED,
        RELEASED,
        FULFILLED
    }

    public MenuHoldCommandResult {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("reservationId must be positive");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
    }

    public static MenuHoldCommandResult noHold(long reservationId) {
        return new MenuHoldCommandResult(reservationId, Outcome.NO_HOLD);
    }

    public static MenuHoldCommandResult confirmed(long reservationId) {
        return new MenuHoldCommandResult(reservationId, Outcome.CONFIRMED);
    }

    public static MenuHoldCommandResult released(long reservationId) {
        return new MenuHoldCommandResult(reservationId, Outcome.RELEASED);
    }

    public static MenuHoldCommandResult fulfilled(long reservationId) {
        return new MenuHoldCommandResult(reservationId, Outcome.FULFILLED);
    }
}
