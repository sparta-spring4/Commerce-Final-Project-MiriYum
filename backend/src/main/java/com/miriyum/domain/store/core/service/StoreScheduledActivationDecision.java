package com.miriyum.domain.store.core.service;

/**
 * 잠긴 Store 행을 기준으로 자동 게시 가능 여부를 판정한 결과다.
 *
 * @param storeId 매장 식별자
 * @param timeZoneId 검증된 IANA 시간대 식별자
 * @param activationAllowed 현재 상태에서 자동 게시 가능한지 여부
 */
public record StoreScheduledActivationDecision(
        long storeId,
        String timeZoneId,
        boolean activationAllowed
) {
}
