package com.miriyum.domain.store.core.dto;

/**
 * 일반 예약 신규 거래에 필요한 Store 조건을 모두 통과한 결과다.
 *
 * @param storeId 검증된 매장 식별자
 */
public record StoreReservationTransactionEligibility(long storeId) {
}
