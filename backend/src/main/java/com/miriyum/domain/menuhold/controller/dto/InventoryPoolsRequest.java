package com.miriyum.domain.menuhold.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record InventoryPoolsRequest(
        @NotNull @Min(0) @Max(1_000_000) Integer onlineHold,
        @NotNull @Min(0) @Max(1_000_000) Integer onsite,
        @NotNull @Min(0) @Max(1_000_000) Integer shared
) {
}
