package com.miriyum.domain.menuhold.inventory.dto;

import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import java.time.LocalDate;
import java.time.LocalTime;

public record InventoryBucketCreateCommand(
        long menuId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        int totalSupply,
        int onlineHoldCapacity,
        int onsiteCapacity,
        int sharedCapacity,
        boolean sharedOnlineAllowed,
        InventoryAvailabilityStatus availabilityStatus
) {
    public InventoryBucketCreateCommand {
        if (menuId <= 0 || serviceDate == null || startTime == null
                || endDate == null || endTime == null || totalSupply < 0
                || onlineHoldCapacity < 0 || onsiteCapacity < 0
                || sharedCapacity < 0 || availabilityStatus == null) {
            throw new IllegalArgumentException("invalid inventory bucket create command");
        }
    }
}
