package com.miriyum.domain.store.core.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 외부 응답 검증을 통과해 현재 매장 주소에 저장할 좌표다.
 * 제공자 메타데이터는 검증 경계를 넘어 영속화하지 않는다.
 */
public record VerifiedStoreGeocoding(
        BigDecimal latitude,
        BigDecimal longitude,
        String verifiedAddress,
        Instant verifiedAt
) {
}
