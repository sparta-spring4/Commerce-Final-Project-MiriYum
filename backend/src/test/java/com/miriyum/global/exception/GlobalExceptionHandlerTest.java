package com.miriyum.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void 서비스_예외를_도메인_오류_응답으로_변환한다() throws Exception {
        mockMvc.perform(get("/test/service-error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEST_001"))
                .andExpect(jsonPath("$.message").value("테스트 대상을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void 요청_본문_검증_오류는_필드별로_정렬한다() throws Exception {
        String invalidBody = """
                {
                  "name": "",
                  "items": [{"quantity": 0}]
                }
                """;

        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.details[0].field").value("items[0].quantity"))
                .andExpect(jsonPath("$.details[0].reason").value("1개 이상이어야 합니다."))
                .andExpect(jsonPath("$.details[1].field").value("name"))
                .andExpect(jsonPath("$.details[1].reason").value("이름은 필수입니다."));
    }

    @Test
    void 요청_파라미터_검증_오류를_매핑한다() throws Exception {
        mockMvc.perform(get("/test/query").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.details[0].field").value("size"))
                .andExpect(jsonPath("$.details[0].reason").value("1 이상이어야 합니다."));
    }

    @Test
    void 경로와_헤더_검증_오류는_공개된_파라미터_이름을_사용한다() throws Exception {
        mockMvc.perform(get("/test/path/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("itemId"));

        mockMvc.perform(get("/test/header").header("X-Retry-Count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("X-Retry-Count"));
    }

    @Test
    void 읽을_수_없는_요청_본문은_안전한_메시지만_반환한다() throws Exception {
        String malformedBody = """
                {"name":"노출되면-안되는-값","items":[}
                """;

        MvcResult result = mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("노출되면-안되는-값");
    }

    @Test
    void 검증에_실패한_실제_값을_응답에_노출하지_않는다() throws Exception {
        String rejectedValue = "secret-rejected-value";
        String body = """
                {"name":"%s","items":[{"quantity":1}]}
                """.formatted(rejectedValue);

        MvcResult result = mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(rejectedValue);
    }

    @Test
    void 존재하지_않는_경로를_공통_404로_변환한다() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_005"));
    }

    @Test
    void 지원하지_않는_메서드와_미디어_타입을_변환한다() throws Exception {
        mockMvc.perform(post("/test/query"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON_006"));

        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_009"));
    }

    @Test
    void 예상하지_못한_예외의_내부_정보를_노출하지_않는다() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_011"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-internal-message");
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/service-error")
        void serviceError() {
            throw new ServiceException(TestErrorCode.NOT_FOUND);
        }

        @PostMapping(value = "/validation", consumes = MediaType.APPLICATION_JSON_VALUE)
        void validateBody(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/query")
        void validateQuery(
                @Min(value = 1, message = "1 이상이어야 합니다.")
                @RequestParam("size") int size
        ) {
        }

        @GetMapping("/path/{itemId}")
        void validatePath(
                @Min(value = 1, message = "1 이상이어야 합니다.")
                @PathVariable("itemId") long itemId
        ) {
        }

        @GetMapping("/header")
        void validateHeader(
                @Min(value = 1, message = "1 이상이어야 합니다.")
                @RequestHeader("X-Retry-Count") int retryCount
        ) {
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("secret-internal-message");
        }
    }

    record TestRequest(
            @NotBlank(message = "이름은 필수입니다.")
            @Size(max = 10, message = "이름은 10자 이하여야 합니다.")
            String name,
            List<@Valid ItemRequest> items
    ) {
    }

    record ItemRequest(
            @Min(value = 1, message = "1개 이상이어야 합니다.")
            int quantity
    ) {
    }

    enum TestErrorCode implements ErrorCode {
        NOT_FOUND;

        @Override
        public HttpStatus getHttpStatus() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String getCode() {
            return "TEST_001";
        }

        @Override
        public String getMessage() {
            return "테스트 대상을 찾을 수 없습니다.";
        }
    }
}
