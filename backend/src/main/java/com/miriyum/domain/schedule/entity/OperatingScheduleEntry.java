package com.miriyum.domain.schedule.entity;

import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.WeeklyInterval;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.DayOfWeek;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatingScheduleEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Enumerated(EnumType.STRING)
    @Column(name = "interval_kind", nullable = false, length = 20)
    private ScheduleIntervalKind intervalKind;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "overnight", nullable = false)
    private boolean overnight;

    @Column(name = "week_start_minute", nullable = false)
    private int weekStartMinute;

    @Column(name = "week_end_minute", nullable = false)
    private int weekEndMinute;

    static OperatingScheduleEntry from(WeeklyInterval interval) {
        if (interval.kind() == ScheduleIntervalKind.RESERVATION_SLOT) {
            throw new IllegalArgumentException("reservation slot is not an operating entry");
        }
        OperatingScheduleEntry entry = new OperatingScheduleEntry();
        entry.dayOfWeek = interval.dayOfWeek();
        entry.intervalKind = interval.kind();
        entry.startTime = interval.startTime();
        entry.endTime = interval.endTime();
        entry.overnight = interval.overnight();
        entry.weekStartMinute = interval.weekStartMinute();
        entry.weekEndMinute = interval.weekEndMinute();
        return entry;
    }

    public WeeklyInterval toWeeklyInterval() {
        return new WeeklyInterval(
                dayOfWeek,
                startTime,
                endTime,
                overnight,
                intervalKind,
                weekStartMinute,
                weekEndMinute);
    }
}
