package com.miriyum.domain.menuhold.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 현재 정책 버전에서 확보할 메뉴·구간·수량 선택이다. */
public record MenuInventoryAcquireSelection(
        long menuId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        long inventoryPolicyVersion,
        int quantity
) {
    public MenuInventoryAcquireSelection {
        if (menuId <= 0) {
            throw new IllegalArgumentException("menuId must be positive");
        }
        if (serviceDate == null || startTime == null || endDate == null || endTime == null
                || !LocalDateTime.of(serviceDate, startTime)
                        .isBefore(LocalDateTime.of(endDate, endTime))) {
            throw new IllegalArgumentException("service time range must be increasing");
        }
        if (inventoryPolicyVersion <= 0) {
            throw new IllegalArgumentException("inventoryPolicyVersion must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
