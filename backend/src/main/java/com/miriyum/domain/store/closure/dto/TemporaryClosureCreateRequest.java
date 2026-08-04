package com.miriyum.domain.store.closure.dto;

import com.miriyum.domain.store.closure.model.TemporaryClosureReason;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record TemporaryClosureCreateRequest(
        @NotNull OffsetDateTime startAt,
        @NotNull OffsetDateTime endAt,
        @NotNull TemporaryClosureReason reason,
        @Size(max = 200) String publicMessage
) {
    @AssertTrue(message = "startAt must be before endAt")
    public boolean isValidInterval() {
        return startAt == null || endAt == null || startAt.toInstant().isBefore(endAt.toInstant());
    }
}
