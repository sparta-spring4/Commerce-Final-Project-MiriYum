package com.miriyum.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessResultTest {

    @Test
    @DisplayName("유효한 성공 업무 결과를 생성한다")
    void validSuccessResult_isCreated() {
        // given
        String data = "response";

        // when
        BusinessResult<String> result =
                new BusinessResult<>(200, "SUCCESS", "ORDER", "order-1", data);

        // then
        assertThat(result.data()).isEqualTo(data);
    }

    @Test
    @DisplayName("성공 범위가 아닌 HTTP 상태는 거부한다")
    void nonSuccessHttpStatus_isRejected() {
        // given
        int[] invalidStatuses = {199, 300};

        // when & then
        for (int status : invalidStatuses) {
            assertThatThrownBy(
                            () ->
                                    new BusinessResult<>(
                                            status, "SUCCESS", "ORDER", "order-1", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("httpStatus");
        }
    }

    @Test
    @DisplayName("SUCCESS가 아닌 응답 코드는 거부한다")
    void invalidResponseCode_isRejected() {
        // given
        String[] invalidCodes = {null, "", " ", "CREATED"};

        // when & then
        for (String responseCode : invalidCodes) {
            assertThatThrownBy(
                            () ->
                                    new BusinessResult<>(
                                            200, responseCode, "ORDER", "order-1", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("responseCode");
        }
    }

    @Test
    @DisplayName("빈 값 또는 DB 컬럼 길이를 넘는 리소스 식별자는 거부한다")
    void invalidResourceIdentifier_isRejected() {
        // given
        String overlongType = "T".repeat(41);
        String overlongId = "I".repeat(65);

        // when & then
        assertThatThrownBy(() -> new BusinessResult<>(200, "SUCCESS", " ", "order-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType");
        assertThatThrownBy(
                        () ->
                                new BusinessResult<>(
                                        200, "SUCCESS", overlongType, "order-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType");
        assertThatThrownBy(() -> new BusinessResult<>(200, "SUCCESS", "ORDER", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceId");
        assertThatThrownBy(
                        () -> new BusinessResult<>(200, "SUCCESS", "ORDER", overlongId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceId");
    }
}
