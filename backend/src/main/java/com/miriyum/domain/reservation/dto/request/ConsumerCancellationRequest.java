package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.Size;

public record ConsumerCancellationRequest(
        @Size(min = 1, max = 500) String reason
) {
}
