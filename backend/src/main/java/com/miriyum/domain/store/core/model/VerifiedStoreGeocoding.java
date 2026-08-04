package com.miriyum.domain.store.core.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 외부 응답 검증을 통과해 현재 매장 주소에 저장할 좌표와 최소 추적 정보다.
 */
public record VerifiedStoreGeocoding(
        BigDecimal latitude,
        BigDecimal longitude,
        String verifiedAddress,
        Instant verifiedAt,
        String provider,
        String providerApiVersion
) {
}
