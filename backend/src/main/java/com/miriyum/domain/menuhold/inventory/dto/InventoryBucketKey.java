package com.miriyum.domain.menuhold.inventory.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record InventoryBucketKey(
        long menuId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        long inventoryPolicyVersion
) {
}
