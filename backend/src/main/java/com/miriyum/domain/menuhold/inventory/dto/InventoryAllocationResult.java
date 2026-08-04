package com.miriyum.domain.menuhold.inventory.dto;

public record InventoryAllocationResult(
        long bucketId,
        int onlineHoldQuantity,
        int sharedQuantity
) {
}
