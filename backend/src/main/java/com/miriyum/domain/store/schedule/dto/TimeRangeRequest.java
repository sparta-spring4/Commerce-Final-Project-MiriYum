package com.miriyum.domain.store.schedule.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record TimeRangeRequest(
        @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @NotNull @JsonFormat(pattern = "HH:mm") LocalTime endTime
) {

    @JsonIgnore
    @AssertTrue(message = "시간은 분 단위여야 합니다.")
    public boolean isMinutePrecision() {
        return hasMinutePrecision(startTime) && hasMinutePrecision(endTime);
    }

    private boolean hasMinutePrecision(LocalTime time) {
        return time == null || (time.getSecond() == 0 && time.getNano() == 0);
    }
}
