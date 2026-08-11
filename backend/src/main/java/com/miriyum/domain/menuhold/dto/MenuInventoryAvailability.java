package com.miriyum.domain.menuhold.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 현재 정책 버전에서 관찰한 메뉴별 온라인 가용량이다. */
public record MenuInventoryAvailability(
        long menuId,
        long inventoryPolicyVersion,
        String timeZoneId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        int availableOnlineQuantity,
        AvailabilityStatus availabilityStatus
) {
    public MenuInventoryAvailability {
        if (menuId <= 0) {
            throw new IllegalArgumentException("menuId must be positive");
        }
        if (inventoryPolicyVersion <= 0) {
            throw new IllegalArgumentException("inventoryPolicyVersion must be positive");
        }
        if (timeZoneId == null || timeZoneId.isBlank()) {
            throw new IllegalArgumentException("timeZoneId must not be blank");
        }
        if (serviceDate == null || startTime == null || endDate == null || endTime == null
                || !LocalDateTime.of(serviceDate, startTime)
                        .isBefore(LocalDateTime.of(endDate, endTime))) {
            throw new IllegalArgumentException("service time range must be increasing");
        }
        if (availableOnlineQuantity < 0) {
            throw new IllegalArgumentException("availableOnlineQuantity must not be negative");
        }
        if (availabilityStatus == null) {
            throw new IllegalArgumentException("availabilityStatus must not be null");
        }
    }

    /** 신규 온라인 거래 가능 여부를 소비자에게 전달하는 공개 상태다. */
    public enum AvailabilityStatus {
        AVAILABLE,
        SOLD_OUT
    }
}
