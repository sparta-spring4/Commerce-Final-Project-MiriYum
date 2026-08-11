package com.miriyum.domain.store.model;

/**
 * 외부 제공자 주소 후보를 검증에 필요한 문자열만 남긴 중립 형식이다.
 */
public record StoreGeocodingCandidate(
        String roadAddress,
        String parcelAddress,
        String region1DepthName,
        String longitude,
        String latitude
) {
}
