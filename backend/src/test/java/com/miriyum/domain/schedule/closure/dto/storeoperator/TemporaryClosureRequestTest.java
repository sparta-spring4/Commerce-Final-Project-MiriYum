package com.miriyum.domain.schedule.closure.dto.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureReason;
import jakarta.validation.Validation;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class TemporaryClosureRequestTest {
    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test void convertsOffsetsAndRejectsReversedInterval() {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-03T18:00:00+09:00");
        TemporaryClosureCreateRequest valid = new TemporaryClosureCreateRequest(start, start.plusHours(1),
                TemporaryClosureReason.MAINTENANCE, "정비");
        assertThat(validator.validate(valid)).isEmpty();
        assertThat(valid.startAt().toInstant()).isEqualTo("2026-08-03T09:00:00Z");
        assertThat(validator.validate(new TemporaryClosureCreateRequest(start, start,
                TemporaryClosureReason.OTHER, null))).isNotEmpty();
    }

    @Test void endChangeRequiresNonBlankReason() {
        OffsetDateTime endAt = OffsetDateTime.parse("2026-08-03T20:00:00+09:00");

        assertThat(validator.validate(new TemporaryClosureEndAtRequest(endAt, "정비 연장")))
                .isEmpty();
        assertThat(validator.validate(new TemporaryClosureEndAtRequest(endAt, " ")))
                .isNotEmpty();
        assertThat(validator.validate(new TemporaryClosureEndAtRequest(endAt, "가".repeat(500))))
                .isEmpty();
        assertThat(validator.validate(new TemporaryClosureEndAtRequest(endAt, "가".repeat(501))))
                .isNotEmpty();
    }
}
