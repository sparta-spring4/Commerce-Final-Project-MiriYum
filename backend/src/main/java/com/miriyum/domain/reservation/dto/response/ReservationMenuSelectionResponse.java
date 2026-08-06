package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;

/** 예약 당시 선택한 메뉴 거래 스냅샷의 고객 공개 항목이다. */
public record ReservationMenuSelectionResponse(
        String menuId,
        String menuName,
        long unitPrice,
        int quantity
) {

    public static ReservationMenuSelectionResponse from(MenuHoldItemResult item) {
        if (item == null) {
            throw new IllegalArgumentException("menu hold item is required");
        }
        return new ReservationMenuSelectionResponse(
                String.valueOf(item.menuId()),
                item.menuName(),
                item.unitPrice(),
                item.quantity()
        );
    }
}
