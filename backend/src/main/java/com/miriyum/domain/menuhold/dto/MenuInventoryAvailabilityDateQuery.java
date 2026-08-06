package com.miriyum.domain.menuhold.dto;

import java.time.LocalDate;
import java.util.List;

/** 픽업 날짜에 게시된 현재 온라인 메뉴 수량 구간을 열거하는 공개 조회 조건이다. */
public record MenuInventoryAvailabilityDateQuery(
        List<Long> menuIds,
        LocalDate pickupDate
) {
    public MenuInventoryAvailabilityDateQuery {
        if (menuIds == null || menuIds.isEmpty()) {
            throw new IllegalArgumentException("menuIds must not be empty");
        }
        if (menuIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("menuId must be positive");
        }
        if (pickupDate == null) {
            throw new IllegalArgumentException("pickupDate must not be null");
        }
        menuIds = menuIds.stream().distinct().sorted().toList();
    }
}
