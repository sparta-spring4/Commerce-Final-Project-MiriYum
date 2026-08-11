package com.miriyum.domain.pickup.dto.response;

public record PickupReservationItemResponse(
        String menuId,
        String menuName,
        int unitPrice,
        int quantity
) {
}
