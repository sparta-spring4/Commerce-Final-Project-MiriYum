package com.miriyum.domain.store.dto.storeoperator;

import jakarta.validation.constraints.NotNull;

public record StoreModesRequest(
        @NotNull
        Boolean reservationEnabled,
        @NotNull
        Boolean menuHoldEnabled,
        @NotNull
        Boolean pickupEnabled
) {
}
