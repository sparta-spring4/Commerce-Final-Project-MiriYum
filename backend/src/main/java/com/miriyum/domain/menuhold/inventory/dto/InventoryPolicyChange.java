package com.miriyum.domain.menuhold.inventory.dto;

import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;

public record InventoryPolicyChange(
        int totalSupply,
        int onlineHoldCapacity,
        int onsiteCapacity,
        int sharedCapacity,
        boolean sharedOnlineAllowed,
        InventoryAvailabilityStatus availabilityStatus
) {

    public InventoryPolicyChange {
        if (totalSupply < 0 || onlineHoldCapacity < 0 || onsiteCapacity < 0
                || sharedCapacity < 0 || availabilityStatus == null) {
            throw new IllegalArgumentException("invalid inventory policy change");
        }
    }
}
