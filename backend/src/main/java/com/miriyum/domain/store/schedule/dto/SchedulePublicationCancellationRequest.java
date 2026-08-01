package com.miriyum.domain.store.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SchedulePublicationCancellationRequest(
        @NotBlank @Size(max = 500) String changeReason
) {
}
