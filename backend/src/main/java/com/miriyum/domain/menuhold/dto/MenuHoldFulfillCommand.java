package com.miriyum.domain.menuhold.dto;

/** 예약 방문 완료 트랜잭션이 메뉴 홀드 이행 완료에 전달하는 공개 명령이다. */
public record MenuHoldFulfillCommand(
        String reservationId,
        String operationId,
        String sourceAcquireOperationId
) {

    public MenuHoldFulfillCommand {
        requireText(reservationId, "reservationId");
        requireText(operationId, "operationId");
        requireText(sourceAcquireOperationId, "sourceAcquireOperationId");
        if (operationId.equals(sourceAcquireOperationId)) {
            throw new IllegalArgumentException(
                    "fulfill operationId must differ from sourceAcquireOperationId");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
