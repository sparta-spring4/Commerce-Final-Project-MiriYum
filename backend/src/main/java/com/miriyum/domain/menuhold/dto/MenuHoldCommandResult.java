package com.miriyum.domain.menuhold.dto;

/** 예약 조정자가 저장하고 재생할 수 있는 메뉴 홀드 명령 결과다. */
public record MenuHoldCommandResult(
        String reservationId,
        Outcome outcome,
        String sourceAcquireOperationId
) {

    public enum Outcome {
        NO_HOLD,
        CONFIRMED,
        RELEASED,
        FULFILLED
    }

    public MenuHoldCommandResult {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (outcome == Outcome.NO_HOLD) {
            if (sourceAcquireOperationId != null) {
                throw new IllegalArgumentException(
                        "NO_HOLD result must not carry sourceAcquireOperationId");
            }
        } else if (sourceAcquireOperationId == null || sourceAcquireOperationId.isBlank()) {
            throw new IllegalArgumentException(
                    "hold result must carry sourceAcquireOperationId");
        }
    }

    public static MenuHoldCommandResult noHold(String reservationId) {
        return new MenuHoldCommandResult(reservationId, Outcome.NO_HOLD, null);
    }

    public static MenuHoldCommandResult confirmed(
            String reservationId,
            String sourceAcquireOperationId
    ) {
        return new MenuHoldCommandResult(
                reservationId, Outcome.CONFIRMED, sourceAcquireOperationId);
    }

    public static MenuHoldCommandResult released(
            String reservationId,
            String sourceAcquireOperationId
    ) {
        return new MenuHoldCommandResult(
                reservationId, Outcome.RELEASED, sourceAcquireOperationId);
    }

    public static MenuHoldCommandResult fulfilled(
            String reservationId,
            String sourceAcquireOperationId
    ) {
        return new MenuHoldCommandResult(
                reservationId, Outcome.FULFILLED, sourceAcquireOperationId);
    }
}
