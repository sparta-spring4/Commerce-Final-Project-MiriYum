package com.miriyum.domain.schedule.dto.storeoperator;

import com.miriyum.domain.schedule.entity.OperatingScheduleEntry;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.ConflictCheckStatus;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import java.time.Instant;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public record OperatingHoursResponse(
        long version,
        ScheduleVersionStatus status,
        String timeZoneId,
        Instant effectiveAt,
        String changeReason,
        ConflictCheckStatus conflictCheckStatus,
        Integer conflictCount,
        List<DailyOperatingScheduleRequest> days
) {

    public OperatingHoursResponse(
            long version,
            List<DailyOperatingScheduleRequest> days
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

    public static OperatingHoursResponse from(OperatingScheduleVersion version) {
        List<OperatingScheduleEntry> entries = version.getEntries();
        List<DailyOperatingScheduleRequest> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyOperatingScheduleRequest(
                        day,
                        ranges(entries, day, ScheduleIntervalKind.BUSINESS_HOURS),
                        ranges(entries, day, ScheduleIntervalKind.BREAK_TIME)))
                .toList();
        return new OperatingHoursResponse(
                version.getVersionNumber(),
                version.getStatus(),
                version.getTimeZoneId(),
                version.getEffectiveAt(),
                version.getChangeReason(),
                version.getConflictCheckStatus(),
                version.getConflictCount(),
                days);
    }

    private static List<TimeRangeRequest> ranges(
            List<OperatingScheduleEntry> entries,
            DayOfWeek day,
            ScheduleIntervalKind kind
    ) {
        return entries.stream()
                .filter(entry -> entry.getDayOfWeek() == day)
                .filter(entry -> entry.getIntervalKind() == kind)
                .sorted(Comparator.comparingInt(
                        OperatingScheduleEntry::getWeekStartMinute))
                .map(entry -> new TimeRangeRequest(
                        entry.getStartTime(),
                        entry.getEndTime()))
                .toList();
    }
}
