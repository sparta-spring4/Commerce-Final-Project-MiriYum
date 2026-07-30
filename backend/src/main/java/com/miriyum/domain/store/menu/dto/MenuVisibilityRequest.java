package com.miriyum.domain.store.menu.dto;

import com.miriyum.domain.store.menu.enums.MenuVisibility;
import jakarta.validation.constraints.NotNull;

public record MenuVisibilityRequest(@NotNull MenuVisibility visibility) {
}
