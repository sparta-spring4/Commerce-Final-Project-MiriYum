package com.miriyum.domain.store.schedule.dto;

import com.miriyum.domain.store.schedule.model.PublicationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import java.time.OffsetDateTime;

public record SchedulePublicationRequest(
        @NotNull PublicationMode publicationMode,
        OffsetDateTime effectiveAt,
        @NotBlank @Size(max = 500) String changeReason
) {
    @AssertTrue(message = "게시 방식과 적용 시각이 일치해야 합니다.")
    public boolean isEffectiveAtValid() {
        return publicationMode == null
                || (publicationMode == PublicationMode.IMMEDIATE
                        ? effectiveAt == null
                        : effectiveAt != null);
    }
}
