package com.miriyum.domain.store.menu.dto;

import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import jakarta.validation.constraints.NotNull;

public record MenuSellingStatusRequest(@NotNull MenuSellingStatus sellingStatus) {
}
