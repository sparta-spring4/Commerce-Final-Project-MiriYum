package com.miriyum.domain.menuhold.controller.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketCreateCommand;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;

public record MenuInventoryCreateRequest(
        @NotNull @Positive Long menuId,
        @NotNull LocalDate serviceDate,
        @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @NotNull LocalDate endDate,
        @NotNull @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        @NotNull @Min(0) @Max(1_000_000) Integer totalSupply,
        @NotNull @Valid InventoryPoolsRequest pools,
        @NotNull Boolean sharedOnlineAllowed,
        @NotNull InventoryAvailabilityStatus availabilityStatus
) {
    @JsonIgnore
    @AssertTrue(message = "시간은 분 단위여야 합니다.")
    public boolean isMinutePrecision() {
        return hasMinutePrecision(startTime) && hasMinutePrecision(endTime);
    }

    public InventoryBucketCreateCommand toCommand() {
        return new InventoryBucketCreateCommand(
                menuId, serviceDate, startTime, endDate, endTime, totalSupply,
                pools.onlineHold(), pools.onsite(), pools.shared(), sharedOnlineAllowed,
                availabilityStatus);
    }

    private boolean hasMinutePrecision(LocalTime time) {
        return time == null || (time.getSecond() == 0 && time.getNano() == 0);
    }
}
