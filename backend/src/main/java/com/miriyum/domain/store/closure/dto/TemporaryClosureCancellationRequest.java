package com.miriyum.domain.store.closure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemporaryClosureCancellationRequest(
        @NotBlank @Size(max = 500) String changeReason
) {
}
