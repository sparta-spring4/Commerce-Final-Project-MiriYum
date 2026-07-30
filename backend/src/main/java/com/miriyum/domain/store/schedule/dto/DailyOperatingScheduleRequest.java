package com.miriyum.domain.store.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.util.List;

public record DailyOperatingScheduleRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull List<@Valid TimeRangeRequest> businessHours,
        @NotNull List<@Valid TimeRangeRequest> breakTimes
) {
}
