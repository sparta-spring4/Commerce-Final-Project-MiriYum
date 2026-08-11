package com.miriyum.domain.store.dto.contract;

/**
 * 메뉴 도메인이 신규 거래 자격을 계산할 때 사용하는 잠금 검증 결과다.
 *
 * @param storeId 매장 식별자
 * @param reservationEnabled 일반 예약 기능 활성 여부
 * @param menuHoldEnabled 메뉴 홀드 기능 활성 여부
 * @param pickupEnabled 픽업 기능 활성 여부
 */
public record StoreMenuTransactionEligibility(
        long storeId,
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled
) {
}
