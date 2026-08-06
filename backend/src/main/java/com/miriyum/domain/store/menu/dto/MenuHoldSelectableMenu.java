package com.miriyum.domain.store.menu.dto;

/**
 * 예약 메뉴 홀드 선택 화면에 노출할 Store 소유의 현재 게시 메뉴 스냅샷이다.
 *
 * @param menuId 메뉴 식별자
 * @param menuName 현재 게시 버전의 표시명
 * @param unitPrice 현재 게시 버전의 단가
 */
public record MenuHoldSelectableMenu(
        long menuId,
        String menuName,
        int unitPrice
) {
    public MenuHoldSelectableMenu {
        if (menuId <= 0) {
            throw new IllegalArgumentException("menuId must be positive");
        }
        if (menuName == null || menuName.isBlank()) {
            throw new IllegalArgumentException("menuName must not be blank");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
    }
}
