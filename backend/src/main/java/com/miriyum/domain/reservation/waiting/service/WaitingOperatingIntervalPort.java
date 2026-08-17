package com.miriyum.domain.reservation.waiting.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Waiting이 Store 일정의 저장 구조 없이 영업 구간을 소비하는 포트다. */
public interface WaitingOperatingIntervalPort {

    List<WaitingOperatingInterval> findUpcoming(
            Set<Long> storeIds,
            Instant fromInclusive,
            Instant toExclusive);

    Optional<WaitingOperatingInterval> lockCurrent(
            long storeId,
            String businessIntervalKey,
            Instant expectedStartsAt,
            Instant expectedEndsAt,
            Instant now);

    List<WaitingOperatingInterval> lockCurrent(
            long storeId,
            LocalDate businessDate,
            Instant now);
}
