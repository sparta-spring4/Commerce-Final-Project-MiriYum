package com.miriyum.domain.reservation.port.dto;

/** 예약 상세 응답에 사용하는 예약 당시 메뉴 선택 스냅샷이다. */
public record ReservationMenuHoldItemSnapshot(
        long menuId,
        String menuName,
        long unitPrice,
        int quantity
) {
}
