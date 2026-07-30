package com.miriyum.domain.store.core.dto;

public record StoreModesRequest(
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled
) {
}
