package com.miriyum.domain.reservation.dto.request;

import org.hibernate.validator.constraints.CodePointLength;

public record ConsumerCancellationRequest(
        @CodePointLength(min = 1, max = 500, message = "{jakarta.validation.constraints.Size.message}")
        String reason
) {
}
