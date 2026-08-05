package com.miriyum.domain.store.closure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record TemporaryClosureEndAtRequest(
        @NotNull OffsetDateTime endAt,
        @NotBlank @Size(max = 500) String changeReason
) {
}
