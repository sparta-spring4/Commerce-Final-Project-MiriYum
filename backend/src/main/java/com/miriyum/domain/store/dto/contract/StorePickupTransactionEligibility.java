package com.miriyum.domain.store.dto.contract;

import java.time.ZoneId;

/**
 * Pickup 신규 거래에 필요한 Store 조건을 모두 통과한 결과다.
 *
 * @param storeId 검증된 매장 식별자
 * @param storeName 거래 시점 스냅샷으로 저장할 검증된 매장명
 * @param timeZoneId 거래 시점의 매장 현지 시각을 계산할 IANA 시간대
 */
public record StorePickupTransactionEligibility(
        long storeId,
        String storeName,
        String timeZoneId
) {
    public StorePickupTransactionEligibility {
        if (storeName == null || storeName.isBlank() || storeName.length() > 100) {
            throw new IllegalArgumentException(
                    "storeName must be between 1 and 100 characters");
        }
        if (!ZoneId.getAvailableZoneIds().contains(timeZoneId)) {
            throw new IllegalArgumentException(
                    "timeZoneId must be a valid IANA identifier");
        }
    }
}
