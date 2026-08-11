package com.miriyum.domain.search.dto.publicapi;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalTime;

public record PublicTimeRange(
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime
) {
}
