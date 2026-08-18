package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.dto.WaitingAnalyticsSnapshot;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Waiting 상태 사건의 asOf 최신 상태만 집계하는 공개 조회 서비스다. */
@Service
public class WaitingAnalyticsQueryService {

    private final WaitingTeamRepository teamRepository;
    private final WaitingStatusEventRepository eventRepository;

    public WaitingAnalyticsQueryService(
            WaitingTeamRepository teamRepository,
            WaitingStatusEventRepository eventRepository
    ) {
        this.teamRepository = teamRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public WaitingAnalyticsSnapshot getDashboardSnapshot(
            long storeId,
            LocalDate businessDate,
            Instant asOf
    ) {
        if (storeId <= 0 || businessDate == null || asOf == null) {
            throw new IllegalArgumentException("storeId, businessDate and asOf are required");
        }
        WaitingStatusEventRepository.WaitingDashboardAggregate aggregate =
                Objects.requireNonNull(eventRepository.aggregateDashboardState(
                        storeId, businessDate, asOf));
        WaitingTeamRepository.WaitingDashboardCheckpoint teamCheckpoint =
                Objects.requireNonNull(teamRepository.dashboardCheckpoint(
                        storeId, businessDate, asOf));

        long maxTeamId = value(teamCheckpoint.getMaxTeamId());
        long maxEventId = value(aggregate.getMaxEventId());
        long maxEventSequence = value(aggregate.getMaxEventSequence());
        long sourceVersion = aggregateVersion(maxTeamId, maxEventId, maxEventSequence);

        return new WaitingAnalyticsSnapshot(
                storeId,
                businessDate,
                asOf,
                value(aggregate.getWaitingTeams()),
                value(aggregate.getCalledTeams()),
                value(aggregate.getWaitingPeople()),
                value(aggregate.getCalledPeople()),
                aggregate.getLongestWaitSeconds(),
                value(aggregate.getConfirmedNoShowTeams()),
                checkpoint(maxTeamId, maxEventId, maxEventSequence),
                instant(aggregate.getDataThroughEpochMicros()),
                sourceVersion,
                false);
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static Instant instant(Long epochMicros) {
        if (epochMicros == null) {
            return null;
        }
        return Instant.ofEpochSecond(
                Math.floorDiv(epochMicros, 1_000_000L),
                Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
    }

    private static String checkpoint(long maxTeamId, long maxEventId, long maxEventSequence) {
        String raw = "waiting-dashboard-v1:" + maxTeamId + ':'
                + maxEventId + ':' + maxEventSequence;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static long aggregateVersion(long... versions) {
        long version = 0L;
        for (long component : versions) {
            version = Math.addExact(version, component);
        }
        return Math.max(1L, version);
    }
}
