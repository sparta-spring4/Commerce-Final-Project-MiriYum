package com.miriyum.domain.pickup.dto.response;

import java.time.LocalDate;
import java.util.List;

public record PickupAvailability(
        String storeId,
        LocalDate pickupDate,
        List<PickupAvailabilitySlot> slots
) {
    public PickupAvailability {
        slots = List.copyOf(slots);
    }
}
