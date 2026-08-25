package com.miriyum.domain.search.repository;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 통합 검색의 관련도·검증 좌표를 포함한 공개 후보 투영이다. */
public record IntegratedStoreSearchCandidate(
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
        int structuredRelevance,
        int relevanceTier,
        BigDecimal latitude,
        BigDecimal longitude
) {
    public IntegratedStoreSearchCandidate(
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
            int relevanceTier,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        this(
                storeId, name, region, address, storeCategoryCode, operationStatus,
                reservationEnabled, menuHoldEnabled, pickupEnabled, createdAt,
                0, relevanceTier, latitude, longitude);
    }
}
