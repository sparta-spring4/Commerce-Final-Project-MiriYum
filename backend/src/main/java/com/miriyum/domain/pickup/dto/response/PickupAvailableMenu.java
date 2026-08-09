package com.miriyum.domain.pickup.dto.response;

import java.time.LocalDate;
import java.time.LocalTime;

public record PickupAvailableMenu(
        String menuId,
        String menuName,
        long unitPrice,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalTime endTime,
        PickupAvailabilityStatus availabilityStatus,
        int availableQuantity
) {
}
