package com.miriyum.domain.menuhold.controller.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.springframework.format.annotation.DateTimeFormat;

public record MenuHoldAvailabilityRequest(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
        @NotNull @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
        @Pattern(regexp = "^[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)$")
        String startOffset
) {
    @AssertTrue(message = "startTime must use minute precision")
    public boolean isMinutePrecision() {
        return startTime == null || (startTime.getSecond() == 0 && startTime.getNano() == 0);
    }

    public ZoneOffset parsedStartOffset() {
        return startOffset == null ? null : ZoneOffset.of(startOffset);
    }
}
