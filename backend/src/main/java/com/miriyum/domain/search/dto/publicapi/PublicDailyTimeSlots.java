package com.miriyum.domain.search.dto.publicapi;

import java.time.DayOfWeek;
import java.util.List;

public record PublicDailyTimeSlots(
        DayOfWeek dayOfWeek,
        List<PublicTimeRange> slots
) {
}
