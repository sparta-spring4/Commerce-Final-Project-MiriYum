package com.miriyum.domain.reservation.waiting.service;

import java.time.Instant;
import java.time.LocalDate;

/** Waiting이 소유하는 Store 영업 구간 값이다. */
public record WaitingOperatingInterval(
        long storeId,
        String businessIntervalKey,
        long operatingScheduleVersion,
        LocalDate businessDate,
        Instant startsAt,
        Instant endsAt,
        String timeZoneId
) {
}
