package com.miriyum.domain.store.model;

import java.util.List;

/**
 * 제공자 응답을 후보 수와 최소 추적 정보로 좁힌 중립 결과다.
 */
public record StoreGeocodingResult(
        int totalCount,
        List<StoreGeocodingCandidate> candidates,
        String provider,
        String providerApiVersion
) {
}
