package com.miriyum.domain.menu.dto.storeoperator;

import com.miriyum.domain.menu.enums.MenuVisibility;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MenuVisibilityRequest(
        @NotNull MenuVisibility visibility,
        @NotBlank @Size(max = 500) String changeReason
) {
}
