package com.miriyum.domain.menu.dto.storeoperator;

import com.miriyum.domain.menu.enums.MenuPublicationMode;
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
