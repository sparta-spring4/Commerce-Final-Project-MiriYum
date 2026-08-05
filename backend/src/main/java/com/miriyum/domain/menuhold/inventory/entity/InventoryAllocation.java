package com.miriyum.domain.menuhold.inventory.entity;

public record InventoryAllocation(int onlineHoldQuantity, int sharedQuantity) {
    public InventoryAllocation {
        if (onlineHoldQuantity < 0 || sharedQuantity < 0
                || onlineHoldQuantity + sharedQuantity <= 0) {
            throw new IllegalArgumentException("inventory allocation must be positive");
        }
    }
}
