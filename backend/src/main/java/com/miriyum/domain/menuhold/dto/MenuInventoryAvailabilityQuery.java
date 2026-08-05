package com.miriyum.domain.menuhold.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 현재 온라인 메뉴 수량을 조회할 검증된 메뉴와 서비스 구간이다. */
public record MenuInventoryAvailabilityQuery(
        List<Long> menuIds,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime
) {
    public MenuInventoryAvailabilityQuery {
        if (menuIds == null || menuIds.isEmpty()) {
            throw new IllegalArgumentException("menuIds must not be empty");
        }
        if (menuIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("menuId must be positive");
        }
        if (serviceDate == null || startTime == null || endDate == null || endTime == null) {
            throw new IllegalArgumentException("service time range must not be null");
        }
        if (!LocalDateTime.of(serviceDate, startTime)
                .isBefore(LocalDateTime.of(endDate, endTime))) {
            throw new IllegalArgumentException("service time range must be increasing");
        }
        menuIds = menuIds.stream().distinct().sorted().toList();
    }
}
