package com.miriyum.domain.store.core.dto;

/**
 * 일반 예약 신규 거래에 필요한 Store 조건을 모두 통과한 결과다.
 *
 * @param storeId 검증된 매장 식별자
 * @param storeName 거래 시점 스냅샷으로 저장할 검증된 매장명
 */
public record StoreReservationTransactionEligibility(
        long storeId,
        String storeName
) {
    public StoreReservationTransactionEligibility {
        if (storeName == null || storeName.isBlank() || storeName.length() > 100) {
            throw new IllegalArgumentException(
                    "storeName must be between 1 and 100 characters");
        }
    }
}
