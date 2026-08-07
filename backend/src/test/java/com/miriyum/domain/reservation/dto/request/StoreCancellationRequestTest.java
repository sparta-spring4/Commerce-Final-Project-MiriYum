package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class StoreCancellationRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void operatorReasonIsRequiredAndOneToFiveHundredCharacters() {
        assertThat(VALIDATOR.validate(new StoreCancellationRequest(null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new StoreCancellationRequest(""))).isNotEmpty();
        assertThat(VALIDATOR.validate(new StoreCancellationRequest(" "))).isEmpty();
        assertThat(VALIDATOR.validate(new StoreCancellationRequest("a".repeat(500)))).isEmpty();
        assertThat(VALIDATOR.validate(new StoreCancellationRequest("a".repeat(501)))).isNotEmpty();
    }
}
