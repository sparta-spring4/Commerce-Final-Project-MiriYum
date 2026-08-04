package com.miriyum.domain.menuhold.dto;

/** 예약 도메인이 메뉴 홀드에 전달하는 최소 메뉴 선택 계약이다. */
public record MenuSelection(long menuId, int quantity) {

    public MenuSelection {
        if (menuId <= 0) {
            throw new IllegalArgumentException("menuId must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
