package com.miriyum.domain.store.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record WeeklyOperatingHoursRequest(
        @NotNull
        @Size(min = 7, max = 7)
        List<@Valid DailyOperatingScheduleRequest> days
) {

    @AssertTrue(message = "일주일의 모든 요일을 중복 없이 포함해야 합니다.")
    public boolean isEachDayExactlyOnce() {
        if (days == null || days.size() != DayOfWeek.values().length) {
            return true;
        }
        Set<DayOfWeek> actual = days.stream()
                .map(DailyOperatingScheduleRequest::dayOfWeek)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return actual.equals(Set.copyOf(Arrays.asList(DayOfWeek.values())));
    }
}
