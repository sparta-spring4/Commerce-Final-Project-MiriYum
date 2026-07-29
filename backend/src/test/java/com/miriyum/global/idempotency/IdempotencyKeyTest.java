package com.miriyum.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    private static final String VALID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    @DisplayName("표준 UUID를 통과시키고 소문자로 정규화한다")
    void parse_validUuid_normalizesLowercase() {
        assertThat(IdempotencyKey.parse("123E4567-E89B-12D3-A456-426614174000").value())
                .isEqualTo(VALID);
    }

    @Test
    @DisplayName("헤더 누락은 COMMON_003이다")
    void parse_missing_common003() {
        assertThatThrownBy(() -> IdempotencyKey.parse(null))
                .asInstanceOf(InstanceOfAssertFactories.type(ServiceException.class))
                .extracting(ServiceException::getErrorCode)
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED);

        assertThatThrownBy(() -> IdempotencyKey.parse(""))
                .asInstanceOf(InstanceOfAssertFactories.type(ServiceException.class))
                .extracting(ServiceException::getErrorCode)
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    @Test
    @DisplayName("UUID가 아닌 값·중괄호·공백·잘못된 길이는 COMMON_004다")
    void parse_malformed_common004() {
        for (String malformed : new String[] {
                "not-a-uuid",
                "{123e4567-e89b-12d3-a456-426614174000}",
                " 123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174000 ",
                "123e4567e89b12d3a456426614174000",
                "123e4567-e89b-12d3-a456-42661417400"}) {
            assertThatThrownBy(() -> IdempotencyKey.parse(malformed))
                    .as("malformed=%s", malformed)
                    .asInstanceOf(InstanceOfAssertFactories.type(ServiceException.class))
                    .extracting(ServiceException::getErrorCode)
                    .isEqualTo(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }
}
