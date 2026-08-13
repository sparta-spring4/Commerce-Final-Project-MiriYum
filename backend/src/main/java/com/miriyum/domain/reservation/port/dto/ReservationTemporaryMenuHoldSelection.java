package com.miriyum.domain.reservation.port.dto;

/** Reservation이 임시 메뉴 선점 포트에 전달하는 메뉴별 양의 수량이다. */
public record ReservationTemporaryMenuHoldSelection(long menuId, int quantity) {

    public ReservationTemporaryMenuHoldSelection {
        if (menuId <= 0) {
            throw new IllegalArgumentException("menuId must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
