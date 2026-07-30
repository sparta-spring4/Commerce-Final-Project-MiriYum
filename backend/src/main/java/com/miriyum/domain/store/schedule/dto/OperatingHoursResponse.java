package com.miriyum.domain.store.schedule.dto;

import com.miriyum.domain.store.schedule.entity.OperatingScheduleEntry;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.model.ScheduleIntervalKind;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public record OperatingHoursResponse(
        long version,
        List<DailyOperatingScheduleRequest> days
) {

    public static OperatingHoursResponse from(OperatingScheduleVersion version) {
        List<OperatingScheduleEntry> entries = version.getEntries();
        List<DailyOperatingScheduleRequest> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyOperatingScheduleRequest(
                        day,
                        ranges(entries, day, ScheduleIntervalKind.BUSINESS_HOURS),
                        ranges(entries, day, ScheduleIntervalKind.BREAK_TIME)))
                .toList();
        return new OperatingHoursResponse(version.getVersionNumber(), days);
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
