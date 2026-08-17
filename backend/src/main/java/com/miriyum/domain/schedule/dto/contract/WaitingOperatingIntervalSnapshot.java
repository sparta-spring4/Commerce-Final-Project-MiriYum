package com.miriyum.domain.schedule.dto.contract;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 다른 도메인이 Store 영업 구간을 저장 구조 없이 소비하는 불변 projection이다.
 */
public record WaitingOperatingIntervalSnapshot(
        long storeId,
        String businessIntervalKey,
        long operatingScheduleVersion,
        LocalDate businessDate,
        Instant startsAt,
        Instant endsAt,
        String timeZoneId
) {
}
