package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReservationTimePolicyPublicationCancellationRequest(
        @NotBlank @Size(max = 500) String changeReason
) {
}
