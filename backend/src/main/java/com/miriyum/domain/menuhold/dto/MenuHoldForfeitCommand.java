package com.miriyum.domain.menuhold.dto;

/** 예약 노쇼 트랜잭션이 메뉴 홀드의 수량 무복구 종결에 전달하는 공개 명령이다. */
public record MenuHoldForfeitCommand(
        long reservationId,
        String operationId
) {

    public MenuHoldForfeitCommand {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("reservationId must be positive");
        }
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
    }
}
