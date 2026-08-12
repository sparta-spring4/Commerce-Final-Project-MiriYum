package com.miriyum.domain.reservation.port.dto;

/** 예약 생성이 메뉴 홀드 포트에 전달하는 메뉴별 수량이다. */
public record ReservationMenuHoldSelection(long menuId, int quantity) {

    public ReservationMenuHoldSelection {
        if (menuId <= 0) {
            throw new IllegalArgumentException("menuId must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
