package com.miriyum.domain.schedule.closure.dto.storeoperator;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

public record RegularClosureDraftRequest(
        @NotNull @Size(max = 7) List<DayOfWeek> weeklyDays,
        @NotNull @Size(max = 366) List<LocalDate> dates
) {
    public RegularClosureDraftRequest {
        weeklyDays = weeklyDays == null ? null : List.copyOf(weeklyDays);
        dates = dates == null ? null : List.copyOf(dates);
    }

    @AssertTrue(message = "regular closure rules must be unique")
    public boolean isUniqueRules() {
        return weeklyDays == null || dates == null
                || (new HashSet<>(weeklyDays).size() == weeklyDays.size()
                && new HashSet<>(dates).size() == dates.size());
    }
}
