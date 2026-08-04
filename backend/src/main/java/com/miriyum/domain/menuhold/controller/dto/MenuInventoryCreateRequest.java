package com.miriyum.domain.menuhold.controller.dto;

import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketCreateCommand;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;

public record MenuInventoryCreateRequest(
        @NotNull @Positive Long menuId,
        @NotNull LocalDate serviceDate,
        @NotNull LocalTime startTime,
        @NotNull LocalDate endDate,
        @NotNull LocalTime endTime,
        @NotNull @Min(0) @Max(1_000_000) Integer totalSupply,
        @NotNull @Valid InventoryPoolsRequest pools,
        @NotNull InventoryAvailabilityStatus availabilityStatus
) {
    public InventoryBucketCreateCommand toCommand() {
        return new InventoryBucketCreateCommand(
                menuId, serviceDate, startTime, endDate, endTime, totalSupply,
                pools.onlineHold(), pools.onsite(), pools.shared(), true,
                availabilityStatus);
    }
}
