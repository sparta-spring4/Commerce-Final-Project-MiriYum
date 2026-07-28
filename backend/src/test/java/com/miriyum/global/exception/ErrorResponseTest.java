package com.miriyum.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ErrorResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 일반_오류는_상세_항목을_노출하지_않는다() throws Exception {
        ErrorResponse response = ErrorResponse.from(CommonErrorCode.ENDPOINT_NOT_FOUND);

        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(response));

        assertThat(json.get("code").asString()).isEqualTo("COMMON_005");
        assertThat(json.get("message").asString()).isEqualTo("요청한 API 경로가 존재하지 않습니다.");
        assertThat(json.has("details")).isFalse();
    }

    @Test
    void 검증_오류는_필드별_사유를_담는다() {
        ValidationErrorDetail detail = new ValidationErrorDetail("items[0].quantity", "1개 이상이어야 합니다.");

        ErrorResponse response = ErrorResponse.of(CommonErrorCode.VALIDATION_FAILED, List.of(detail));

        assertThat(response.details()).containsExactly(detail);
    }

    @Test
    void 상세_항목은_불변_복사된다() {
        List<ValidationErrorDetail> details =
                new java.util.ArrayList<>(List.of(new ValidationErrorDetail("name", "필수입니다.")));

        ErrorResponse response = ErrorResponse.of(CommonErrorCode.VALIDATION_FAILED, details);
        details.clear();

        assertThat(response.details()).hasSize(1);
    }
}
