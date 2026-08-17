package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class ReservationCheckInRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsOnlyStrictVersionedTwoHundredFiftySixBitToken() {
        assertThat(VALIDATOR.validate(new ReservationCheckInRequest(
                "rqg_v1_" + "A".repeat(43)
        ))).isEmpty();
        assertThat(VALIDATOR.validate(new ReservationCheckInRequest(null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new ReservationCheckInRequest(""))).isNotEmpty();
        assertThat(VALIDATOR.validate(new ReservationCheckInRequest(
                "rqg_v1_" + "A".repeat(42)
        ))).isNotEmpty();
        assertThat(VALIDATOR.validate(new ReservationCheckInRequest(
                "rqg_v2_" + "A".repeat(43)
        ))).isNotEmpty();
        assertThat(VALIDATOR.validate(new ReservationCheckInRequest(
                "rqg_v1_" + "+".repeat(43)
        ))).isNotEmpty();
    }
}
