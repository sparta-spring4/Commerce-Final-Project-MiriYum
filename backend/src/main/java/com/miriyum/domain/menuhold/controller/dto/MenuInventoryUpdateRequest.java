package com.miriyum.domain.menuhold.controller.dto;

import com.miriyum.domain.menuhold.inventory.dto.InventoryPolicyChange;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MenuInventoryUpdateRequest(
        @NotNull @Min(0) @Max(1_000_000) Integer totalSupply,
        @NotNull @Valid InventoryPoolsRequest pools,
        @NotNull Boolean sharedOnlineAllowed,
        @NotNull InventoryAvailabilityStatus availabilityStatus
) {
    public InventoryPolicyChange toCommand() {
        return new InventoryPolicyChange(
                totalSupply, pools.onlineHold(), pools.onsite(), pools.shared(),
                sharedOnlineAllowed, availabilityStatus);
    }
}
