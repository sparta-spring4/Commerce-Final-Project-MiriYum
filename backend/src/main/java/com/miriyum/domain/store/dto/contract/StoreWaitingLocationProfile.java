package com.miriyum.domain.store.dto.contract;

import java.math.BigDecimal;

/** Waiting이 Store 구현 타입 없이 소비하는 위치 판정 기준점 계약이다. */
public record StoreWaitingLocationProfile(
        long storeId,
        BigDecimal latitude,
        BigDecimal longitude,
        long coordinateVersion,
        boolean locationProofEligible
) {
    public StoreWaitingLocationProfile {
        if (storeId <= 0 || coordinateVersion < 0) {
            throw new IllegalArgumentException("store location identifiers must be valid");
        }
        if (locationProofEligible && (latitude == null || longitude == null)) {
            throw new IllegalArgumentException("eligible location requires coordinates");
        }
        if (!locationProofEligible && (latitude != null || longitude != null)) {
            throw new IllegalArgumentException("ineligible location must not expose coordinates");
        }
    }
}
