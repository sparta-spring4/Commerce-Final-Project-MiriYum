package com.miriyum.domain.store.schedule.dto;

import com.miriyum.domain.store.schedule.entity.ReservationScheduleEntry;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.model.ConflictCheckStatus;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import java.time.Instant;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public record ReservationTimeSlotsResponse(
        long version,
        ScheduleVersionStatus status,
        String timeZoneId,
        Instant effectiveAt,
        String changeReason,
        ConflictCheckStatus conflictCheckStatus,
        Integer conflictCount,
        List<DailyReservationSlotsRequest> days
) {

    public ReservationTimeSlotsResponse(
            long version,
            List<DailyReservationSlotsRequest> days
    ) {
        this(
                version,
                ScheduleVersionStatus.ACTIVE,
                "Asia/Seoul",
                null,
                null,
                ConflictCheckStatus.NOT_EVALUATED,
                null,
                days);
    }

    public static ReservationTimeSlotsResponse from(
            ReservationScheduleVersion version
    ) {
        List<ReservationScheduleEntry> entries = version.getEntries();
        List<DailyReservationSlotsRequest> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyReservationSlotsRequest(
                        day,
                        entries.stream()
                                .filter(entry -> entry.getDayOfWeek() == day)
                                .sorted(Comparator.comparingInt(
                                        ReservationScheduleEntry::getWeekStartMinute))
                                .map(entry -> new TimeRangeRequest(
                                        entry.getStartTime(),
                                        entry.getEndTime()))
                                .toList()))
                .toList();
        return new ReservationTimeSlotsResponse(
                version.getVersionNumber(),
                version.getStatus(),
                version.getTimeZoneId(),
                version.getEffectiveAt(),
                version.getChangeReason(),
                version.getConflictCheckStatus(),
                version.getConflictCount(),
                days);
    }
}
