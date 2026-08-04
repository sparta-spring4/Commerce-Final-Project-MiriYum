package com.miriyum.domain.store.search.dto;

import java.time.DayOfWeek;
import java.util.List;

public record PublicDailyTimeSlots(
        DayOfWeek dayOfWeek,
        List<PublicTimeRange> slots
) {
}
