package com.miriyum.domain.menuhold.dto;

/** 예약 방문 완료 트랜잭션이 메뉴 홀드 이행 완료에 전달하는 공개 명령이다. */
public record MenuHoldFulfillCommand(
        long reservationId,
        String operationId
) {

    public MenuHoldFulfillCommand {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("reservationId must be positive");
        }
        requireText(operationId, "operationId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
