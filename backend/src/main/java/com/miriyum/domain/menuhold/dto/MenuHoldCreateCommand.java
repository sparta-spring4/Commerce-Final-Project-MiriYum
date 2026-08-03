package com.miriyum.domain.menuhold.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 예약 생성 트랜잭션이 메뉴 홀드 생성에 전달하는 공개 명령이다. */
public record MenuHoldCreateCommand(
        String reservationId,
        String storeId,
        String consumerAccountId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        String operationId,
        List<MenuSelection> menuSelections
) {

    public MenuHoldCreateCommand {
        requireText(reservationId, "reservationId");
        requireText(storeId, "storeId");
        requireText(consumerAccountId, "consumerAccountId");
        if (serviceDate == null) {
            throw new IllegalArgumentException("serviceDate must not be null");
        }
        if (startTime == null || endDate == null || endTime == null
                || !LocalDateTime.of(serviceDate, startTime)
                        .isBefore(LocalDateTime.of(endDate, endTime))) {
            throw new IllegalArgumentException("service time range must be increasing");
        }
        requireText(operationId, "operationId");
        if (menuSelections == null) {
            throw new IllegalArgumentException("menuSelections must not be null");
        }
        if (menuSelections.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("menuSelections must not contain null");
        }
        Map<String, Integer> quantitiesByMenuId = new LinkedHashMap<>();
        for (MenuSelection selection : menuSelections) {
            try {
                quantitiesByMenuId.merge(
                        selection.menuId(), selection.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "menu selection quantity sum exceeds integer range", exception);
            }
        }
        menuSelections = quantitiesByMenuId.entrySet().stream()
                .map(entry -> new MenuSelection(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
