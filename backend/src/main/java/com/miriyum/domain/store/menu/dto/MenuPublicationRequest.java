package com.miriyum.domain.store.menu.dto;

import com.miriyum.domain.store.menu.enums.MenuPublicationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record MenuPublicationRequest(
        @NotNull MenuPublicationMode mode,
        Instant effectiveAt,
        @NotBlank @Size(max = 500) String changeReason
) {
}
