package com.miriyum.domain.search.dto.publicapi;

import java.time.DayOfWeek;
import java.util.List;

public record PublicDailySchedule(
        DayOfWeek dayOfWeek,
        List<PublicTimeRange> businessHours,
        List<PublicTimeRange> breakTimes
) {
}
