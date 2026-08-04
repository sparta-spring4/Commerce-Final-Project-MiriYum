package com.miriyum.domain.store.schedule.dto;

import java.time.Instant;

public record StoreServiceIntervalRequest(long storeId, Instant startAt, Instant serviceEndAt) {
    public StoreServiceIntervalRequest {
        if (storeId <= 0 || startAt == null || serviceEndAt == null || !startAt.isBefore(serviceEndAt)) {
            throw new IllegalArgumentException("valid service interval is required");
        }
    }
}
