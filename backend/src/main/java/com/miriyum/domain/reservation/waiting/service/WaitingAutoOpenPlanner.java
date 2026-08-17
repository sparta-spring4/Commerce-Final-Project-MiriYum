package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.repository.WaitingAutoOpenJobRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WaitingAutoOpenPlanner {

    private static final Duration MAXIMUM_ADVANCE = Duration.ofMinutes(180);
    private final WaitingSettingRepository settingRepository;
    private final WaitingAutoOpenJobRepository jobRepository;
    private final WaitingOperatingIntervalPort intervalPort;
    private final AtomicLong planningAfterStoreId = new AtomicLong();

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public int plan(Instant now, Duration horizon, int batchSize) {
        if (now == null || horizon == null || horizon.isNegative() || horizon.isZero()
                || batchSize <= 0) {
            throw new IllegalArgumentException("planning window is invalid");
        }
        long afterStoreId = planningAfterStoreId.get();
        List<WaitingSetting> candidates = settingRepository.findAutoOpenPlanningCandidates(
                afterStoreId, PageRequest.of(0, batchSize));
        if (candidates.isEmpty() && afterStoreId != 0L) {
            planningAfterStoreId.compareAndSet(afterStoreId, 0L);
            candidates = settingRepository.findAutoOpenPlanningCandidates(
                    0L, PageRequest.of(0, batchSize));
        }
        if (candidates.isEmpty()) {
            return 0;
        }
        planningAfterStoreId.set(candidates.getLast().getStoreId());
        Map<Long, WaitingSetting> byStore = candidates.stream()
                .collect(Collectors.toUnmodifiableMap(
                        WaitingSetting::getStoreId,
                        Function.identity()));
        Instant horizonEnd = now.plus(horizon);
        List<WaitingOperatingInterval> intervals = intervalPort.findUpcoming(
                Set.copyOf(byStore.keySet()),
                now,
                horizonEnd.plus(MAXIMUM_ADVANCE));
        int created = 0;
        for (WaitingOperatingInterval interval : intervals) {
            WaitingSetting planned = byStore.get(interval.storeId());
            if (planned == null || !interval.endsAt().isAfter(now)) {
                continue;
            }
            Instant scheduledAt = interval.startsAt()
                    .minus(Duration.ofMinutes(planned.getAdvanceOpenMinutes()));
            if (scheduledAt.isAfter(horizonEnd)) {
                continue;
            }
            WaitingSetting current = settingRepository.findByStoreId(interval.storeId())
                    .orElse(null);
            if (!matches(planned, current)) {
                continue;
            }
            WaitingAutoOpenJob job = WaitingAutoOpenJob.pending(
                    interval.storeId(),
                    interval.businessIntervalKey(),
                    interval.businessDate(),
                    interval.startsAt(),
                    interval.endsAt(),
                    scheduledAt,
                    planned.getVersion(),
                    planned.getAdvanceOpenMinutes(),
                    WaitingAutoOpenIdempotencyKey.from(
                            interval.storeId(),
                            interval.businessIntervalKey(),
                            planned.getVersion()),
                    now);
            if (jobRepository.rearmInvalidated(job, now) == 1) {
                created++;
            } else {
                created += Math.min(jobRepository.insertPending(job), 1);
            }
        }
        return created;
    }

    private static boolean matches(WaitingSetting planned, WaitingSetting current) {
        return current != null
                && current.isEnabled()
                && current.getReceptionMode() == WaitingReceptionMode.AUTO
                && current.getVersion() == planned.getVersion()
                && current.getAdvanceOpenMinutes() == planned.getAdvanceOpenMinutes();
    }
}
