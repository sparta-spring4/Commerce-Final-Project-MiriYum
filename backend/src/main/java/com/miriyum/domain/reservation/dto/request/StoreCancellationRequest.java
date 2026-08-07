package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StoreCancellationRequest(
        @NotNull @Size(min = 1, max = 500) String reason
) {
}
