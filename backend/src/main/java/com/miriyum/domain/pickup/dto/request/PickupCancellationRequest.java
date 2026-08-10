package com.miriyum.domain.pickup.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PickupCancellationRequest(
        @Size(min = 1, max = 500)
        @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*")
        String reason
) {
}
