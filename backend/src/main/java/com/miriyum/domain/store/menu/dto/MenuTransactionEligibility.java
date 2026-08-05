package com.miriyum.domain.store.menu.dto;

/**
 * 신규 메뉴 홀드·픽업 거래가 사용할 Store 소유의 메뉴 판정이다.
 *
 * @param storeId 검증된 매장 식별자
 * @param menuId 검증된 메뉴 식별자
 * @param publishedVersionNumber 현재 게시 메뉴 버전 번호
 * @param menuName 현재 게시 메뉴 버전의 표시명
 * @param unitPrice 현재 게시 메뉴 버전의 단가
 * @param menuHoldEligible 메뉴 홀드 거래 선택 가능 여부
 * @param pickupEligible 픽업 거래 선택 가능 여부
 */
public record MenuTransactionEligibility(
        long storeId,
        long menuId,
        int publishedVersionNumber,
        String menuName,
        int unitPrice,
        boolean menuHoldEligible,
        boolean pickupEligible
) {
    public MenuTransactionEligibility {
        if (menuName == null || menuName.isBlank()) {
            throw new IllegalArgumentException("menuName must not be blank");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
    }
}
