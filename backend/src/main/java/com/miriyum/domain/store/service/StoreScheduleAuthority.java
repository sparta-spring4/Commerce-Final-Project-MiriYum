package com.miriyum.domain.store.service;

/**
 * 일정 command가 잠긴 Store 행에서 확인한 권한과 시간대 값이다.
 *
 * @param storeId 잠긴 매장 식별자
 * @param timeZoneId 검증된 IANA 시간대 식별자
 */
public record StoreScheduleAuthority(long storeId, String timeZoneId) {
}
