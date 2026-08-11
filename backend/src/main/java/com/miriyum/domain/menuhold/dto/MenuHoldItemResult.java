package com.miriyum.domain.menuhold.dto;

/** 예약 상세 조합에 사용하는 예약 당시 메뉴 선택 거래 스냅샷이다. */
public record MenuHoldItemResult(
        long menuId,
        String menuName,
        long unitPrice,
        int quantity
) {
}
