package com.miriyum.domain.store.search.dto;

/**
 * 공개 검색 결과에 노출하는 매장 이용 방식 활성 상태다.
 *
 * @param reservationEnabled 예약 기능 활성 여부
 * @param menuHoldEnabled 메뉴 홀드 기능 활성 여부
 * @param pickupEnabled 픽업 기능 활성 여부
 */
public record PublicStoreModes(
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled
) {
}
