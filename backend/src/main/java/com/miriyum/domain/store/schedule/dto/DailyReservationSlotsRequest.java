package com.miriyum.domain.store.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.util.List;

public record DailyReservationSlotsRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull
        @Size(max = 48)
        List<@NotNull @Valid TimeRangeRequest> slots
) {
}
