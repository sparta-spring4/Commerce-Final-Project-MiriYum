package com.miriyum.domain.menuhold.entity;

public record MenuHoldItemSnapshot(
        long menuId,
        long menuInventoryBucketId,
        long menuPolicyVersion,
        long inventoryPolicyVersion,
        int quantity
) {
    public MenuHoldItemSnapshot {
        if (menuId <= 0 || menuInventoryBucketId <= 0 || menuPolicyVersion <= 0
                || inventoryPolicyVersion <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("invalid menu hold item snapshot");
        }
    }
}
