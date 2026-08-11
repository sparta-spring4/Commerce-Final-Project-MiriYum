package com.miriyum.domain.pickup.dto.response;

import java.time.LocalTime;
import java.util.List;

public record PickupAvailabilitySlot(
        LocalTime pickupTime,
        List<PickupAvailableMenu> menus
) {
    public PickupAvailabilitySlot {
        menus = List.copyOf(menus);
    }
}
