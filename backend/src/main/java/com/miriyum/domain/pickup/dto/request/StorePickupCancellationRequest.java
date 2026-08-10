package com.miriyum.domain.pickup.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StorePickupCancellationRequest(
        @NotBlank
        @Size(max = 500)
        @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*")
        String reason
) {
}
