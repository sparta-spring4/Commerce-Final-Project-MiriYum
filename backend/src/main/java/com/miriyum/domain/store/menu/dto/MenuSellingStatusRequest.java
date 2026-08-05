package com.miriyum.domain.store.menu.dto;

import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MenuSellingStatusRequest(
        @NotNull MenuSellingStatus sellingStatus,
        @NotBlank @Size(max = 500) String changeReason
) {
}
