package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.enums.PickupEligibility;

/**
 * 메뉴 신규 명령이 잠긴 Store 행에서 확인한 운영 권한과 픽업 자격이다.
 *
 * @param storeId 잠긴 매장 식별자
 * @param pickupEligibility 메뉴 픽업 선택 허용 검증에 사용하는 중앙 자격
 */
public record StoreMenuAuthority(
        long storeId,
        PickupEligibility pickupEligibility
) {
}
