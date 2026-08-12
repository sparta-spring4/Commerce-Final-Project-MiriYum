package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;

import java.util.Set;

class ConsumerCancellationRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void consumerReasonMayBeNullButMustBeOneToFiveHundredWhenPresent() {
        assertThat(VALIDATOR.validate(new ConsumerCancellationRequest(null))).isEmpty();
        assertThat(VALIDATOR.validate(new ConsumerCancellationRequest(" "))).isEmpty();
        assertThat(VALIDATOR.validate(new ConsumerCancellationRequest(""))).isNotEmpty();
        assertThat(VALIDATOR.validate(new ConsumerCancellationRequest("a".repeat(500)))).isEmpty();
        assertThat(VALIDATOR.validate(new ConsumerCancellationRequest("a".repeat(501)))).isNotEmpty();
    }

    @Test
    void countsSupplementaryConsumerReasonByUnicodeCodePoint() {
        String fiveHundredCodePoints = "😀".repeat(500);
        String fiveHundredOneCodePoints = "😀".repeat(501);

        assertThat(VALIDATOR.validate(
                new ConsumerCancellationRequest(fiveHundredCodePoints))).isEmpty();
        Set<ConstraintViolation<ConsumerCancellationRequest>> violations = VALIDATOR.validate(
                new ConsumerCancellationRequest(fiveHundredOneCodePoints));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("reason");
    }
}
