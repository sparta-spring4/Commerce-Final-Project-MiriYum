package com.miriyum.domain.store.menu.dto;

import com.miriyum.domain.store.menu.enums.MenuVisibility;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MenuVisibilityRequest(
        @NotNull MenuVisibility visibility,
        @NotBlank @Size(max = 500) String changeReason
) {
}
