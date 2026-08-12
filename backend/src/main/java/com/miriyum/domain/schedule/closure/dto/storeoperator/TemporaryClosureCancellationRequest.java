package com.miriyum.domain.schedule.closure.dto.storeoperator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemporaryClosureCancellationRequest(
        @NotBlank @Size(max = 500) String changeReason
) {
}
