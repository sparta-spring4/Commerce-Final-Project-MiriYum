package com.miriyum.domain.store.dto.contract;

/**
 * 웨이팅 접수 가능 여부를 Store 저장 구조와 분리해 공개한다.
 */
public record StoreWaitingReceptionProfile(
        long storeId,
        String timeZoneId,
        boolean waitingReceptionEligible
) {
}
