package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;

import java.util.Set;

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

    @Test
    void countsSupplementaryOperatorReasonByUnicodeCodePoint() {
        String fiveHundredCodePoints = "😀".repeat(500);
        String fiveHundredOneCodePoints = "😀".repeat(501);

        assertThat(VALIDATOR.validate(
                new StoreCancellationRequest(fiveHundredCodePoints))).isEmpty();
        Set<ConstraintViolation<StoreCancellationRequest>> violations = VALIDATOR.validate(
                new StoreCancellationRequest(fiveHundredOneCodePoints));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("reason");
    }
}
