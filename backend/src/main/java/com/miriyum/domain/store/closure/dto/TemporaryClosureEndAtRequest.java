package com.miriyum.domain.store.closure.dto;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record TemporaryClosureEndAtRequest(@NotNull OffsetDateTime endAt) {
}
