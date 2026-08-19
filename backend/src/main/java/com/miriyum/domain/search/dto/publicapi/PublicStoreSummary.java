package com.miriyum.domain.search.dto.publicapi;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;

/**
 * 가용성 판정 상태를 포함한 공개 매장 검색 요약이다.
 *
 * @param storeId JavaScript 정밀도 손실을 피하기 위한 문자열 매장 식별자
 * @param name 매장명
 * @param region 영업 지역
 * @param address 매장 주소
 * @param storeCategoryCode 주 카테고리 코드
 * @param operationStatus 현재 운영 상태
 * @param modes 매장 이용 방식 활성 상태
 * @param reservationAvailability 예약 가용성 판정 상태
 * @param coordinates 현재 주소 버전에 결합된 공개 검증 좌표, 없으면 null
 */
public record PublicStoreSummary(
        String storeId,
        String name,
        Region region,
        String address,
        String storeCategoryCode,
        OperationStatus operationStatus,
        PublicStoreModes modes,
        ReservationAvailability reservationAvailability,
        PublicStoreCoordinates coordinates
) {

    public PublicStoreSummary(
            String storeId,
            String name,
            Region region,
            String address,
            String storeCategoryCode,
            OperationStatus operationStatus,
            PublicStoreModes modes,
            ReservationAvailability reservationAvailability
    ) {
        this(storeId, name, region, address, storeCategoryCode, operationStatus,
                modes, reservationAvailability, null);
    }
}
