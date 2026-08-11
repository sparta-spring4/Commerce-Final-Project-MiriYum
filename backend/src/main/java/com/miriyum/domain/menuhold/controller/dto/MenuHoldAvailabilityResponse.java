package com.miriyum.domain.menuhold.controller.dto;

import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability.AvailabilityStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record MenuHoldAvailabilityResponse(
        LocalDate serviceDate,
        OffsetDateTime startAt,
        OffsetDateTime serviceEndAt,
        String timeZoneId,
        List<Item> items
) {
    public record Item(
            String menuId,
            String menuName,
            int unitPrice,
            int availableOnlineQuantity,
            AvailabilityStatus availabilityStatus
    ) {}
}
