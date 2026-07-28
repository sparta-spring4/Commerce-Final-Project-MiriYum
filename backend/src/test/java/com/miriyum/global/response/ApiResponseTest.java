package com.miriyum.global.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    @DisplayName("성공 응답은 고정 코드와 전달받은 메시지 및 데이터를 반환한다")
    void successWrapsMessageAndDataWithSuccessCode() {
        ApiResponse<String> response = ApiResponse.success("요청 성공", "result");

        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.message()).isEqualTo("요청 성공");
        assertThat(response.data()).isEqualTo("result");
    }

    @Test
    @DisplayName("반환 데이터가 없는 성공 응답도 data 필드를 null로 직렬화한다")
    void successSerializesNullData() throws Exception {
        String json = jsonMapper.writeValueAsString(ApiResponse.success("요청 성공", null));
        JsonNode root = jsonMapper.readTree(json);

        assertThat(root.get("code").asString()).isEqualTo("SUCCESS");
        assertThat(root.get("message").asString()).isEqualTo("요청 성공");
        assertThat(root.has("data")).isTrue();
        assertThat(root.get("data").isNull()).isTrue();
    }
}
