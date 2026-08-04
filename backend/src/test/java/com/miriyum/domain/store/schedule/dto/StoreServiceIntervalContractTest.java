package com.miriyum.domain.store.schedule.dto;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StoreServiceIntervalContractTest {
    @Test void requiresPositiveOrderedInterval() {
        Instant now = Instant.parse("2026-08-03T09:00:00Z");
        assertThatThrownBy(() -> new StoreServiceIntervalRequest(0, now, now.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreServiceIntervalRequest(1, now, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
