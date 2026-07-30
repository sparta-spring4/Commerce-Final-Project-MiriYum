package com.miriyum.domain.store.menu.dto;

import com.miriyum.domain.store.menu.enums.MenuPublicationMode;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record MenuPublicationRequest(
        @NotNull MenuPublicationMode mode,
        Instant effectiveAt
) {
}
