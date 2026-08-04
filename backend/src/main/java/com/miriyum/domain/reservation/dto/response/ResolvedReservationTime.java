package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Reservation 계산 결과를 영속성 타입 없이 전달하는 불변 scalar DTO다.
 */
public record ResolvedReservationTime(
        LocalDate serviceDate,
        Instant startAt,
        Instant serviceEndAt,
        Instant occupancyEndAt,
        String timeZoneId,
        int startOffsetSeconds,
        int serviceEndOffsetSeconds,
        int occupancyEndOffsetSeconds,
        int slotIntervalMinutes,
        int serviceDurationMinutes,
        int turnoverDurationMinutes,
        long policyStoreId,
        long policyVersion
) {

    private static final int MAX_DURATION_MINUTES = 1440;
    private static final int MAX_OFFSET_SECONDS = 18 * 60 * 60;

    public ResolvedReservationTime {
        if (serviceDate == null || startAt == null || serviceEndAt == null
                || occupancyEndAt == null || timeZoneId == null
                || timeZoneId.isBlank()) {
            throw new IllegalArgumentException("resolved reservation time fields are required");
        }
        if (!startAt.isBefore(serviceEndAt)
                || serviceEndAt.isAfter(occupancyEndAt)) {
            throw new IllegalArgumentException("resolved reservation time order is invalid");
        }
        requireOffset(startOffsetSeconds);
        requireOffset(serviceEndOffsetSeconds);
        requireOffset(occupancyEndOffsetSeconds);
        if (slotIntervalMinutes < 1 || slotIntervalMinutes > MAX_DURATION_MINUTES
                || serviceDurationMinutes < 1
                || serviceDurationMinutes > MAX_DURATION_MINUTES
                || turnoverDurationMinutes < 0
                || turnoverDurationMinutes > MAX_DURATION_MINUTES
                || serviceDurationMinutes + turnoverDurationMinutes
                > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException("resolved reservation durations are invalid");
        }
        if (policyStoreId <= 0 || policyVersion <= 0) {
            throw new IllegalArgumentException("resolved reservation policy identity is invalid");
        }
    }

    public static ResolvedReservationTime from(ReservationTimeSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasResolvedTime()) {
            throw new IllegalArgumentException("resolved reservation time snapshot is required");
        }
        return new ResolvedReservationTime(
                snapshot.getServiceDate(),
                snapshot.getStartAt(),
                snapshot.getServiceEndAt(),
                snapshot.getOccupancyEndAt(),
                snapshot.getTimeZoneId(),
                snapshot.getStartOffsetSeconds(),
                snapshot.getServiceEndOffsetSeconds(),
                snapshot.getOccupancyEndOffsetSeconds(),
                snapshot.getSlotIntervalMinutes(),
                snapshot.getServiceDurationMinutes(),
                snapshot.getTurnoverDurationMinutes(),
                snapshot.getReservationTimePolicyStoreId(),
                snapshot.getReservationTimePolicyVersion()
        );
    }

    private static void requireOffset(int offsetSeconds) {
        if (offsetSeconds < -MAX_OFFSET_SECONDS || offsetSeconds > MAX_OFFSET_SECONDS) {
            throw new IllegalArgumentException("resolved reservation offset is invalid");
        }
    }
}
