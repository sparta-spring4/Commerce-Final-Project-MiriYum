package com.miriyum.domain.store.search.dto;

import java.time.DayOfWeek;
import java.util.List;

public record PublicDailySchedule(
        DayOfWeek dayOfWeek,
        List<PublicTimeRange> businessHours,
        List<PublicTimeRange> breakTimes
) {
}
