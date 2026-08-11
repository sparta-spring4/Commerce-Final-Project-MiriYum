package com.miriyum.domain.schedule.dto.storeoperator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.util.List;

public record DailyOperatingScheduleRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull
        @Size(max = 48)
        List<@NotNull @Valid TimeRangeRequest> businessHours,
        @NotNull
        @Size(max = 48)
        List<@NotNull @Valid TimeRangeRequest> breakTimes
) {
}
