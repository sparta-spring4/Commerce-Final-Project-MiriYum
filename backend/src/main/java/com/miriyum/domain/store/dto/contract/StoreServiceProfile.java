package com.miriyum.domain.store.dto.contract;

/**
 * 일정 도메인이 서비스 구간을 판정할 때 사용하는 매장 공개 조회 계약이다.
 *
 * @param storeId 매장 식별자
 * @param timeZoneId 검증된 IANA 시간대 식별자
 * @param reservationAccepting 현재 예약 기능을 받을 수 있는 매장 상태인지 여부
 */
public record StoreServiceProfile(
        long storeId,
        String timeZoneId,
        boolean reservationAccepting
) {
}
