package com.miriyum.domain.store.search.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 예약 가능 매장 검색이 한 요청에서 평가할 공개 후보 수를 제한한다.
 */
@Component
public final class StoreSearchCandidateLimit {

    public static final int HARD_MAXIMUM = 5_000;

    private final int value;

    public StoreSearchCandidateLimit(
            @Value("${miriyum.store-search.available-candidate-limit}") int value
    ) {
        if (value < 1 || value > HARD_MAXIMUM) {
            throw new IllegalArgumentException(
                    "available candidate limit must be between 1 and 5000");
        }
        this.value = value;
    }

    public int value() {
        return value;
    }
}
