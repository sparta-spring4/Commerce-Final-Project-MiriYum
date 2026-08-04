package com.miriyum.domain.menuhold.dto;

/** 예약 취소 트랜잭션이 홀드 해제와 원 확보 복구에 전달하는 공개 명령이다. */
public record MenuHoldReleaseCommand(
        String reservationId,
        String operationId
) {

    public MenuHoldReleaseCommand {
        requireText(reservationId, "reservationId");
        requireText(operationId, "operationId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
