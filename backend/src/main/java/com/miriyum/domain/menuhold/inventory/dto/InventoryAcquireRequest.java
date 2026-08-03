package com.miriyum.domain.menuhold.inventory.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record InventoryAcquireRequest(String commandId, List<Selection> selections) {
    public InventoryAcquireRequest {
        if (commandId == null || commandId.isBlank() || commandId.length() > 100
                || selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("inventory acquire request is required");
        }
        selections = List.copyOf(selections);
    }

    public record Selection(
            long menuId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalDate endDate,
            LocalTime endTime,
            long inventoryPolicyVersion,
            int quantity
    ) {
        public Selection {
            if (menuId <= 0 || serviceDate == null || startTime == null || endDate == null
                    || endTime == null || !java.time.LocalDateTime.of(serviceDate, startTime)
                            .isBefore(java.time.LocalDateTime.of(endDate, endTime))
                    || inventoryPolicyVersion <= 0
                    || quantity <= 0) {
                throw new IllegalArgumentException("invalid inventory selection");
            }
        }

        public InventoryBucketKey key() {
            return new InventoryBucketKey(
                    menuId, serviceDate, startTime, endDate, endTime, inventoryPolicyVersion);
        }
    }
}
