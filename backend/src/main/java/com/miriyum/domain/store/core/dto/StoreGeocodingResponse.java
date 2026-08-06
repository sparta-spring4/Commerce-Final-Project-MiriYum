package com.miriyum.domain.store.core.dto;

import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.GeocodingStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 운영자에게 공개하는 현재 매장 주소의 안전한 좌표 검증 상태다.
 */
public record StoreGeocodingResponse(
        GeocodingStatus status,
        BigDecimal latitude,
        BigDecimal longitude,
        String verifiedAddress,
        Instant verifiedAt,
        long addressVersion
) {

    private static final long LEGACY_ADDRESS_VERSION = 1L;

    public static StoreGeocodingResponse legacyUnverified() {
        return new StoreGeocodingResponse(
                GeocodingStatus.UNVERIFIED,
                null,
                null,
                null,
                null,
                LEGACY_ADDRESS_VERSION);
    }

    public static StoreGeocodingResponse from(Store store) {
        return new StoreGeocodingResponse(
                store.getGeocodingStatus(),
                store.getLatitude(),
                store.getLongitude(),
                store.getVerifiedAddress(),
                store.getGeocodingVerifiedAt(),
                store.getAddressVersion());
    }
}
