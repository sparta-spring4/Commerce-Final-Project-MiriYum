package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

public record StoreCancellationRequest(
        @NotNull
        @CodePointLength(min = 1, max = 500, message = "{jakarta.validation.constraints.Size.message}")
        String reason
) {
}
