package com.miriyum.domain.reservation.port.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 예약 생성 트랜잭션이 메뉴 홀드 포트에 전달하는 명령이다. */
public record ReservationMenuHoldCreateCommand(
        long reservationId,
        long storeId,
        long consumerAccountId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        Instant startAt,
        Instant serviceEndAt,
        String operationId,
        List<ReservationMenuHoldSelection> menuSelections
) {

    public ReservationMenuHoldCreateCommand {
        requirePositive(reservationId, "reservationId");
        requirePositive(storeId, "storeId");
        requirePositive(consumerAccountId, "consumerAccountId");
        if (serviceDate == null) {
            throw new IllegalArgumentException("serviceDate must not be null");
        }
        if (startTime == null || endDate == null || endTime == null
                || !LocalDateTime.of(serviceDate, startTime)
                        .isBefore(LocalDateTime.of(endDate, endTime))) {
            throw new IllegalArgumentException("service time range must be increasing");
        }
        if (startAt == null || serviceEndAt == null || !startAt.isBefore(serviceEndAt)) {
            throw new IllegalArgumentException("resolved service time range must be increasing");
        }
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        if (menuSelections == null
                || menuSelections.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("menuSelections must not contain null");
        }
        Map<Long, Integer> quantitiesByMenuId = new LinkedHashMap<>();
        for (ReservationMenuHoldSelection selection : menuSelections) {
            try {
                quantitiesByMenuId.merge(
                        selection.menuId(), selection.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "menu selection quantity sum exceeds integer range", exception);
            }
        }
        menuSelections = quantitiesByMenuId.entrySet().stream()
                .map(entry -> new ReservationMenuHoldSelection(
                        entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
