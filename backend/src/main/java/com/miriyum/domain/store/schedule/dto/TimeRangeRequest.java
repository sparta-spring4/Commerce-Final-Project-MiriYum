package com.miriyum.domain.store.schedule.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record TimeRangeRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
) {
}
