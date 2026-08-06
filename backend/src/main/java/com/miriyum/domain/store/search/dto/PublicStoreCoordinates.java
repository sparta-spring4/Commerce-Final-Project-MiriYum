package com.miriyum.domain.store.search.dto;

import java.math.BigDecimal;
import java.util.Objects;

/** 현재 주소 버전에 결합된 공개 검증 좌표다. */
public record PublicStoreCoordinates(
        BigDecimal latitude,
        BigDecimal longitude
) {

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

    public PublicStoreCoordinates {
        requireRange(latitude, MIN_LATITUDE, MAX_LATITUDE, "latitude");
        requireRange(longitude, MIN_LONGITUDE, MAX_LONGITUDE, "longitude");
    }

    private static void requireRange(
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum,
            String fieldName
    ) {
        BigDecimal required = Objects.requireNonNull(value, fieldName + " is required");
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(fieldName + " is outside the coordinate range");
        }
    }
}
