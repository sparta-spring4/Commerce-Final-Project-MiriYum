package com.miriyum.domain.schedule.closure.dto.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegularClosureRequestTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsExplicitEmptyClosureSchedule() {
        assertThat(validator.validate(
                new RegularClosureDraftRequest(List.of(), List.of())))
                .isEmpty();
    }

    @Test
    void rejectsDuplicateWeeklyAndDateRules() {
        RegularClosureDraftRequest request = new RegularClosureDraftRequest(
                List.of(DayOfWeek.MONDAY, DayOfWeek.MONDAY),
                List.of(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 25)));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("uniqueRules");
    }
}
