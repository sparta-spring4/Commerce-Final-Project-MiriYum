package com.miriyum.domain.schedule.model;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record WeeklyInterval(
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        boolean overnight,
        ScheduleIntervalKind kind,
        int weekStartMinute,
        int weekEndMinute
) {

    public WeeklyInterval placedAt(int startMinute, int endMinute) {
        int owningDayEnd = dayOfWeek.getValue() * 1440;
        return new WeeklyInterval(
                dayOfWeek,
                startTime,
                endTime,
                endMinute > owningDayEnd,
                kind,
                startMinute,
                endMinute);
    }
}
