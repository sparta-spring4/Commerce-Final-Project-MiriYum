package com.miriyum.domain.consumer.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsumerSignUpRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("supplementary 문자가 포함된 64 code point 비밀번호는 @Size에서 거부되지 않는다")
    void acceptsSixtyFourCodePointPasswordContainingSupplementaryCharacter() {
        // given: surrogate pair(2 UTF-16 code unit)인 이모지 1개를 포함해 64 code point,
        // 65 UTF-16 code unit인 비밀번호. 예전 @Size(max = 64)라면 잘못 거부되었을 값이다.
        String password = "Aa1" + "b".repeat(60) + "😀";
        ConsumerSignUpRequest request = new ConsumerSignUpRequest(
                "user@example.com", password, password,
                "010-1234-5678", true, "닉네임");

        // when
        Set<ConstraintViolation<ConsumerSignUpRequest>> violations = validator.validate(request);

        // then
        assertThat(password.codePointCount(0, password.length())).isEqualTo(64);
        assertThat(violations)
                .extracting(ConstraintViolation::getPropertyPath)
                .extracting(Object::toString)
                .doesNotContain("password");
    }
}
