package com.miriyum.domain.pickup.dto.response;

import com.miriyum.domain.pickup.entity.PickupCancellationActor;
import com.miriyum.domain.pickup.entity.PickupStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

public record PickupReservationResponse(
        String pickupReservationId,
        String storeId,
        String storeName,
        LocalDate pickupDate,
        LocalTime pickupTime,
        PickupStatus status,
        List<PickupReservationItemResponse> items,
        PickupCancellationActor cancelledBy,
        String cancellationReason,
        OffsetDateTime createdAt
) {
    public PickupReservationResponse {
        items = List.copyOf(items);
    }
}
