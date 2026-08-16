package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.contract.ReservationAnalyticsSnapshot;
import com.miriyum.domain.reservation.repository.ReservationCancellationAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationFulfillmentAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reservation 소유 테이블을 하나의 반복 읽기 snapshot으로 집계하는 공개 조회 서비스다. */
@Service
public class ReservationAnalyticsQueryService {

    private final ReservationRepository reservationRepository;
    private final ReservationCapacityBucketRepository bucketRepository;
    private final ReservationCapacityAllocationRepository allocationRepository;
    private final ReservationCancellationAuditRepository cancellationAuditRepository;
    private final ReservationFulfillmentAuditRepository fulfillmentAuditRepository;

    public ReservationAnalyticsQueryService(
            ReservationRepository reservationRepository,
            ReservationCapacityBucketRepository bucketRepository,
            ReservationCapacityAllocationRepository allocationRepository,
            ReservationCancellationAuditRepository cancellationAuditRepository,
            ReservationFulfillmentAuditRepository fulfillmentAuditRepository
    ) {
        this.reservationRepository = reservationRepository;
        this.bucketRepository = bucketRepository;
        this.allocationRepository = allocationRepository;
        this.cancellationAuditRepository = cancellationAuditRepository;
        this.fulfillmentAuditRepository = fulfillmentAuditRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public ReservationAnalyticsSnapshot getDashboardSnapshot(
            long storeId,
            LocalDate businessDate,
            Instant asOf
    ) {
        if (storeId <= 0 || businessDate == null || asOf == null) {
            throw new IllegalArgumentException("storeId, businessDate and asOf are required");
        }

        ReservationRepository.ReservationAnalyticsLifecycle lifecycle = Objects.requireNonNull(
                reservationRepository.aggregateDashboardLifecycle(storeId, businessDate, asOf));
        ReservationCapacityBucketRepository.ReservationCapacityOfferAnalytics offers =
                Objects.requireNonNull(bucketRepository.aggregateDashboardOffers(
                        storeId, businessDate, asOf));
        ReservationCapacityAllocationRepository.ReservationCapacityUsageAnalytics usage =
                Objects.requireNonNull(allocationRepository.aggregateDashboardUsage(
                        storeId, businessDate, asOf));
        ReservationCancellationAuditRepository.ReservationCancellationAnalytics cancellations =
                Objects.requireNonNull(cancellationAuditRepository.aggregateDashboardCancellations(
                        storeId, businessDate, asOf));
        ReservationFulfillmentAuditRepository.ReservationFulfillmentAnalytics fulfillments =
                Objects.requireNonNull(fulfillmentAuditRepository.aggregateDashboardFulfillments(
                        storeId, businessDate, asOf));

        long reservationVersion = value(lifecycle.getMaxReservationId());
        long capacityPolicyVersion = value(offers.getPolicyVersion());
        long allocationVersion = value(usage.getMaxAllocationId());
        long bucketVersion = value(offers.getMaxBucketId());
        long cancellationVersion = value(cancellations.getMaxAuditId());
        long fulfillmentVersion = value(fulfillments.getMaxAuditId());
        long sourceVersion = Math.max(1L, Stream.of(
                        reservationVersion,
                        value(lifecycle.getMaxCapacityPolicyVersion()),
                        capacityPolicyVersion,
                        allocationVersion,
                        bucketVersion,
                        cancellationVersion,
                        fulfillmentVersion)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L));

        String checkpoint = checkpoint(
                reservationVersion,
                capacityPolicyVersion,
                allocationVersion,
                bucketVersion,
                cancellationVersion,
                fulfillmentVersion);
        Instant dataThrough = Stream.of(
                        instant(lifecycle.getDataThroughEpochMicros()),
                        instant(offers.getDataThroughEpochMicros()),
                        instant(cancellations.getDataThroughEpochMicros()),
                        instant(fulfillments.getDataThroughEpochMicros()))
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        return new ReservationAnalyticsSnapshot(
                storeId,
                businessDate,
                asOf,
                value(lifecycle.getTodayReservationTeams()),
                value(usage.getReservedPeopleUnits()),
                value(offers.getOfferedPeopleUnits()),
                value(usage.getReservedTeamUnits()),
                value(offers.getOfferedTeamUnits()),
                value(lifecycle.getCancelledTeams()),
                value(lifecycle.getEverConfirmedTeams()),
                checkpoint,
                dataThrough,
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
        long seconds = Math.floorDiv(epochMicros, 1_000_000L);
        long micros = Math.floorMod(epochMicros, 1_000_000L);
        return Instant.ofEpochSecond(seconds, micros * 1_000L);
    }

    private static String checkpoint(long... versions) {
        StringBuilder raw = new StringBuilder("reservation-dashboard-v1");
        for (long version : versions) {
            raw.append(':').append(version);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
