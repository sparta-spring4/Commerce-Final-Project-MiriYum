package com.miriyum.domain.pickup.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PickupMenuSelectionRequest(
        @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String menuId,
        @Min(1) @Max(100) int quantity
) {
    public long menuIdAsLong() {
        try {
            long value = Long.parseLong(menuId);
            if (value <= 0) {
                throw new IllegalArgumentException("menuId must be positive");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("menuId must be a positive long", exception);
        }
    }
}
