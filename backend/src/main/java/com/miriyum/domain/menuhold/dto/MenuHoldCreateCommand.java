package com.miriyum.domain.menuhold.dto;

import java.util.List;

/** 예약 생성 트랜잭션이 메뉴 홀드 생성에 전달하는 공개 명령이다. */
public record MenuHoldCreateCommand(
        String reservationId,
        String operationId,
        List<MenuSelection> menuSelections
) {

    public MenuHoldCreateCommand {
        requireText(reservationId, "reservationId");
        requireText(operationId, "operationId");
        if (menuSelections == null) {
            throw new IllegalArgumentException("menuSelections must not be null");
        }
        menuSelections = List.copyOf(menuSelections);
        if (menuSelections.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("menuSelections must not contain null");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
