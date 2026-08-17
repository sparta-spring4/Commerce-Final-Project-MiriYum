package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.entity.ReservationNoShowReason;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class ReservationNoShowRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requiresAnExplicitNoShowReason() {
        assertThat(VALIDATOR.validate(new ReservationNoShowRequest(
                ReservationNoShowReason.UNCLEAR
        ))).isEmpty();
        assertThat(VALIDATOR.validate(new ReservationNoShowRequest(null))).isNotEmpty();
    }
}
