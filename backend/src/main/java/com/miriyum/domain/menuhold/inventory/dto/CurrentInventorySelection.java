package com.miriyum.domain.menuhold.inventory.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record CurrentInventorySelection(
        long menuId,
        long bucketId,
        long inventoryPolicyVersion,
        String timeZoneId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        int quantity
) {
    public CurrentInventorySelection {
        if (menuId <= 0 || bucketId <= 0 || inventoryPolicyVersion <= 0
                || timeZoneId == null || timeZoneId.isBlank() || serviceDate == null
                || startTime == null || endDate == null || endTime == null || quantity <= 0) {
            throw new IllegalArgumentException("invalid current inventory selection");
        }
    }
}
