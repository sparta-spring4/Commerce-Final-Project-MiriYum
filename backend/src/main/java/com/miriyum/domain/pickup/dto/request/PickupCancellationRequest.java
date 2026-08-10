package com.miriyum.domain.pickup.dto.request;

import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record PickupCancellationRequest(
        @CodePointLength(
                min = 1,
                max = 500,
                message = "{jakarta.validation.constraints.Size.message}")
        @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*")
        String reason
) {
}
