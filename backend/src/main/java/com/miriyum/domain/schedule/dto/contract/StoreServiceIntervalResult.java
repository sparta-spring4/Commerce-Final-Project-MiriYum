package com.miriyum.domain.schedule.dto.contract;

import java.time.Instant;

public record StoreServiceIntervalResult(
        long storeId, Instant startAt, Instant serviceEndAt, StoreServiceIntervalStatus status
) {
    public static StoreServiceIntervalResult of(StoreServiceIntervalRequest request, boolean accepting) {
        return new StoreServiceIntervalResult(request.storeId(), request.startAt(), request.serviceEndAt(),
                accepting ? StoreServiceIntervalStatus.ACCEPTING : StoreServiceIntervalStatus.NOT_ACCEPTING);
    }
}
