package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.schedule.dto.contract.WaitingOperatingIntervalSnapshot;
import com.miriyum.domain.schedule.service.WaitingOperatingIntervalService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Schedule 공개 계약을 Waiting 소유 값으로 변환하는 유일한 경계 adapter다. */
@Component
@RequiredArgsConstructor
public class StoreScheduleWaitingOperatingIntervalAdapter
        implements WaitingOperatingIntervalPort {

    private final WaitingOperatingIntervalService intervalService;

    @Override
    public List<WaitingOperatingInterval> findUpcoming(
            Set<Long> storeIds,
            Instant fromInclusive,
            Instant toExclusive
    ) {
        return intervalService.findWaitingOperatingIntervals(
                        storeIds,
                        fromInclusive,
                        toExclusive)
                .stream()
                .map(StoreScheduleWaitingOperatingIntervalAdapter::toWaitingInterval)
                .toList();
    }

    @Override
    public Optional<WaitingOperatingInterval> lockCurrent(
            long storeId,
            String businessIntervalKey,
            Instant expectedStartsAt,
            Instant expectedEndsAt,
            Instant now
    ) {
        return intervalService.lockCurrentWaitingOperatingInterval(
                        storeId,
                        businessIntervalKey,
                        expectedStartsAt,
                        expectedEndsAt,
                        now)
                .map(StoreScheduleWaitingOperatingIntervalAdapter::toWaitingInterval);
    }

    @Override
    public List<WaitingOperatingInterval> lockCurrent(
            long storeId,
            LocalDate businessDate,
            Instant now
    ) {
        return intervalService.lockCurrentWaitingOperatingIntervals(storeId, businessDate, now)
                .stream()
                .map(StoreScheduleWaitingOperatingIntervalAdapter::toWaitingInterval)
                .toList();
    }

    private static WaitingOperatingInterval toWaitingInterval(
            WaitingOperatingIntervalSnapshot snapshot
    ) {
        return new WaitingOperatingInterval(
                snapshot.storeId(),
                snapshot.businessIntervalKey(),
                snapshot.operatingScheduleVersion(),
                snapshot.businessDate(),
                snapshot.startsAt(),
                snapshot.endsAt(),
                snapshot.timeZoneId());
    }
}
