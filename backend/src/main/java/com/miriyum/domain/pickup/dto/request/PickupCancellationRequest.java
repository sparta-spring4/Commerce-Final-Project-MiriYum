package com.miriyum.domain.pickup.dto.request;

import jakarta.validation.constraints.Size;

public record PickupCancellationRequest(
        @Size(min = 1, max = 500) String reason
) {
}
