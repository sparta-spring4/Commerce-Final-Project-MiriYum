package com.miriyum.domain.store.menu.dto;

/**
 * 신규 메뉴 홀드·픽업 거래가 사용할 Store 소유의 메뉴 판정이다.
 *
 * @param storeId 검증된 매장 식별자
 * @param menuId 검증된 메뉴 식별자
 * @param publishedVersionNumber 현재 게시 메뉴 버전 번호
 * @param menuHoldEligible 메뉴 홀드 거래 선택 가능 여부
 * @param pickupEligible 픽업 거래 선택 가능 여부
 */
public record MenuTransactionEligibility(
        long storeId,
        long menuId,
        int publishedVersionNumber,
        boolean menuHoldEligible,
        boolean pickupEligible
) {
}
