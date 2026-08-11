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
        // when & then
        assertThat(IdempotencyKey.parse("123E4567-E89B-12D3-A456-426614174000").value())
                .isEqualTo(VALID);
    }

    @Test
    @DisplayName("UUID version 1~5와 RFC variant 8, 9, a, b를 대소문자 구분 없이 허용한다")
    void parse_rfcUuidVersionsAndVariants_caseInsensitive() {
        // given
        String[][] validValues = {
                {"123E4567-E89B-12D3-8456-426614174000", "123e4567-e89b-12d3-8456-426614174000"},
                {"123e4567-e89b-22d3-9456-426614174000", "123e4567-e89b-22d3-9456-426614174000"},
                {"123e4567-e89b-32d3-a456-426614174000", "123e4567-e89b-32d3-a456-426614174000"},
                {"123e4567-e89b-42d3-b456-426614174000", "123e4567-e89b-42d3-b456-426614174000"},
                {"123e4567-e89b-52d3-B456-426614174000", "123e4567-e89b-52d3-b456-426614174000"}
        };

        // when & then
        for (String[] valid : validValues) {
            assertThat(IdempotencyKey.parse(valid[0]).value())
                    .as("valid=%s", valid[0])
                    .isEqualTo(valid[1]);
        }
    }

    @Test
    @DisplayName("잘못된 UUID version은 COMMON_004로 거절한다")
    void parse_invalidUuidVersion_common004() {
        // given
        String[] invalidVersions = {
                "123e4567-e89b-02d3-8456-426614174000",
                "123e4567-e89b-62d3-8456-426614174000",
                "123e4567-e89b-f2d3-8456-426614174000"
        };

        // when & then
        for (String invalidVersion : invalidVersions) {
            assertThatThrownBy(() -> IdempotencyKey.parse(invalidVersion))
                    .as("invalidVersion=%s", invalidVersion)
                    .asInstanceOf(InstanceOfAssertFactories.type(ServiceException.class))
                    .extracting(ServiceException::getErrorCode)
                    .isEqualTo(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }

    @Test
    @DisplayName("잘못된 UUID variant는 COMMON_004로 거절한다")
    void parse_invalidUuidVariant_common004() {
        // given
        String[] invalidVariants = {
                "123e4567-e89b-12d3-0456-426614174000",
                "123e4567-e89b-12d3-7456-426614174000",
                "123e4567-e89b-12d3-c456-426614174000",
                "123e4567-e89b-12d3-f456-426614174000",
                "123e4567-e89b-12d3-F456-426614174000"
        };

        // when & then
        for (String invalidVariant : invalidVariants) {
            assertThatThrownBy(() -> IdempotencyKey.parse(invalidVariant))
                    .as("invalidVariant=%s", invalidVariant)
                    .asInstanceOf(InstanceOfAssertFactories.type(ServiceException.class))
                    .extracting(ServiceException::getErrorCode)
                    .isEqualTo(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }

    @Test
    @DisplayName("헤더 누락은 COMMON_003이다")
    void parse_missing_common003() {
        // when & then
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
        // given
        String[] malformedValues = {
                "not-a-uuid",
                "{123e4567-e89b-12d3-a456-426614174000}",
                " 123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174000 ",
                "123e4567e89b12d3a456426614174000",
                "123e4567-e89b-12d3-a456-42661417400"
        };

        // when & then
        for (String malformed : malformedValues) {
            assertThatThrownBy(() -> IdempotencyKey.parse(malformed))
                    .as("malformed=%s", malformed)
                    .asInstanceOf(InstanceOfAssertFactories.type(ServiceException.class))
                    .extracting(ServiceException::getErrorCode)
                    .isEqualTo(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }
}
