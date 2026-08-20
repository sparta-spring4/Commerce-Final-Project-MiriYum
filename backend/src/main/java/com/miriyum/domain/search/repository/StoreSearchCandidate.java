package com.miriyum.domain.search.repository;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 공개 검색 조건을 통과한 매장의 가용성 계산 전 읽기 투영이다.
 *
 * @param storeId 매장 식별자
 * @param name 매장명
 * @param region 영업 지역
 * @param address 매장 주소
 * @param addressVersion 후보 스냅샷의 주소 버전
 * @param storeCategoryCode 주 카테고리 코드
 * @param operationStatus 현재 운영 상태
 * @param reservationEnabled 예약 기능 활성 여부
 * @param menuHoldEnabled 메뉴 홀드 기능 활성 여부
 * @param pickupEnabled 픽업 기능 활성 여부
 * @param createdAt 매장 생성 시각
 * @param latitude 현재 주소 버전에 대해 검증된 위도, 없으면 null
 * @param longitude 현재 주소 버전에 대해 검증된 경도, 없으면 null
 */
public record StoreSearchCandidate(
        long storeId,
        String name,
        Region region,
        String address,
        long addressVersion,
        String storeCategoryCode,
        OperationStatus operationStatus,
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled,
        LocalDateTime createdAt,
        BigDecimal latitude,
        BigDecimal longitude
) {

    public StoreSearchCandidate(
            long storeId,
            String name,
            Region region,
            String address,
            String storeCategoryCode,
            OperationStatus operationStatus,
            boolean reservationEnabled,
            boolean menuHoldEnabled,
            boolean pickupEnabled,
            LocalDateTime createdAt
    ) {
        this(storeId, name, region, address, 0, storeCategoryCode, operationStatus,
                reservationEnabled, menuHoldEnabled, pickupEnabled, createdAt,
                null, null);
    }

    public StoreSearchCandidate(
            long storeId,
            String name,
            Region region,
            String address,
            String storeCategoryCode,
            OperationStatus operationStatus,
            boolean reservationEnabled,
            boolean menuHoldEnabled,
            boolean pickupEnabled,
            LocalDateTime createdAt,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        this(storeId, name, region, address, 0, storeCategoryCode, operationStatus,
                reservationEnabled, menuHoldEnabled, pickupEnabled, createdAt,
                latitude, longitude);
    }
}
