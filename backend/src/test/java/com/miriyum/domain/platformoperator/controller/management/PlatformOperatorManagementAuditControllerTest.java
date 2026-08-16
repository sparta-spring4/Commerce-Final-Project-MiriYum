package com.miriyum.domain.platformoperator.controller.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.platformoperator.controller.audit.PlatformOperatorAuditController;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditService;
import com.miriyum.domain.platformoperator.service.PlatformOperatorManagementService;
import com.miriyum.domain.platformoperator.service.PlatformOperatorManagementRequestFingerprint;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.IdempotencyCommand;
import jakarta.validation.Validation;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.ObjectMapper;

class PlatformOperatorManagementAuditControllerTest {

    private MockMvc mvc;
    private PlatformOperatorManagementService managementService;
    private PlatformOperatorAuditService auditService;
    private PlatformOperatorPrincipal principal;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        managementService = mock(PlatformOperatorManagementService.class);
        auditService = mock(PlatformOperatorAuditService.class);
        principal = new PlatformOperatorPrincipal(
                1L, "super@example.com", "session-1", 3L, 3L, false);
        objectMapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(
                        new PlatformOperatorManagementController(managementService,
                                new PlatformOperatorManagementRequestFingerprint(
                                        "test-only-management-fingerprint-secret")),
                        new PlatformOperatorAuditController(auditService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PrincipalResolver())
                .setValidator(new SpringValidatorAdapter(
                        Validation.buildDefaultValidatorFactory().getValidator()))
                .build();
    }

    @Test
    @DisplayName("운영자 생성 HTTP 응답에는 임시 비밀번호가 노출되지 않는다")
    void createAccount_returnsCreatedWithoutTemporaryPassword() throws Exception {
        when(managementService.createAccount(any(), any(), any(), any(), any(Long.class), any(), any()))
                .thenReturn(new IdempotentOutcome(false, 201, "SUCCESS", "PLATFORM_OPERATOR_ACCOUNT", "7",
                        objectMapper.readTree("""
                                {"operatorId":"7","email":"new@example.com","displayName":"new operator",
                                 "status":"ACTIVE","passwordChangeRequired":true,"authorityVersion":1,
                                 "roles":["AUDIT_READER"],"directPermissions":["AUDIT_READ"]}
                                """)));

        mvc.perform(post("/api/v1/platform-operators/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer platform-token")
                        .header("Idempotency-Key", "123e4567-e89b-12d3-a456-426614174000")
                        .header("X-Admin-Reauthentication", "approval")
                        .header("X-Admin-Case-Id", "operator-management-7")
                        .header("X-Admin-Case-Version", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provisioningId":"d9428888-122b-4ef8-bf85-b6258f3652be",
                                 "email":"new@example.com","displayName":"new operator",
                                 "temporaryPassword":"Password1!","roles":["AUDIT_READER"],
                                 "directPermissions":["AUDIT_READ"],"reason":"ACCOUNT_PROVISIONING"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.operatorId").value("7"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Password1!"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("temporaryPassword"))));
    }

    @Test
    @DisplayName("같은 멱등 키라도 임시 비밀번호가 다르면 계정 생성 요청 지문이 달라진다")
    void createAccount_differentTemporaryPasswordProducesDifferentFingerprint() throws Exception {
        when(managementService.createAccount(any(), any(), any(), any(), any(Long.class), any(), any()))
                .thenReturn(new IdempotentOutcome(false, 201, "SUCCESS", "PLATFORM_OPERATOR_ACCOUNT", "7",
                        objectMapper.readTree("{\"operatorId\":\"7\"}")));

        performCreate("Password1!").andExpect(status().isCreated());
        performCreate("Password2@").andExpect(status().isCreated());

        ArgumentCaptor<IdempotencyCommand> commands = ArgumentCaptor.forClass(IdempotencyCommand.class);
        verify(managementService, times(2)).createAccount(
                commands.capture(), any(), any(), any(), any(Long.class), any(), any());
        assertThat(commands.getAllValues().get(0).requestFingerprint())
                .isNotEqualTo(commands.getAllValues().get(1).requestFingerprint());
        assertThat(commands.getAllValues())
                .extracting(IdempotencyCommand::requestFingerprint)
                .allSatisfy(fingerprint -> assertThat(fingerprint).matches("^[0-9a-f]{64}$"));
    }

    @Test
    void auditSearchRequiresStructuredReasonHeader() throws Exception {
        mvc.perform(get("/api/v1/platform-operators/audit-events")
                        .header("X-Admin-Case-Id", "audit-review-1")
                        .header("X-Admin-Case-Version", "1"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAuditSearchQueries")
    @DisplayName("감사 검색 조건이 OpenAPI 계약을 위반하면 COMMON_001을 반환한다")
    void auditSearchRejectsInvalidContractValues(String caseName, Map<String, String> query)
            throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/platform-operators/audit-events")
                .header("X-Admin-Case-Id", "audit-review-1")
                .header("X-Admin-Case-Version", "1")
                .header("X-Admin-Reason-Code", "AUDIT_VERIFICATION");
        query.forEach((name, value) -> request.queryParam(name, value));

        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        verifyNoInteractions(auditService);
    }

    private static Stream<Arguments> invalidAuditSearchQueries() {
        return Stream.of(
                Arguments.of("음수 페이지", Map.of("page", "-1")),
                Arguments.of("0인 페이지 크기", Map.of("size", "0")),
                Arguments.of("최대치를 넘는 페이지 크기", Map.of("size", "101")),
                Arguments.of("역전된 발생 일시 범위", Map.of(
                        "occurredFrom", "2026-08-15T02:00:00Z",
                        "occurredTo", "2026-08-15T01:00:00Z")),
                Arguments.of("알 수 없는 원천", Map.of("source", "OTHER")),
                Arguments.of("형식이 잘못된 행위자 ID", Map.of("actorOperatorId", "operator id")),
                Arguments.of("최대 길이를 넘는 행위자 ID", Map.of(
                        "actorOperatorId", "a".repeat(101))),
                Arguments.of("형식이 잘못된 대상 ID", Map.of("targetId", "target/id")),
                Arguments.of("최대 길이를 넘는 대상 ID", Map.of("targetId", "t".repeat(101))),
                Arguments.of("형식이 잘못된 원 사건 키", Map.of("originalEventKey", "AUTH:0")),
                Arguments.of("최대 길이를 넘는 원 사건 키", Map.of(
                        "originalEventKey", "ADMIN:12345678901234567890")));
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(String temporaryPassword)
            throws Exception {
        return mvc.perform(post("/api/v1/platform-operators/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer platform-token")
                .header("Idempotency-Key", "123e4567-e89b-12d3-a456-426614174000")
                .header("X-Admin-Reauthentication", "approval")
                .header("X-Admin-Case-Id", "operator-management-7")
                .header("X-Admin-Case-Version", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"provisioningId":"d9428888-122b-4ef8-bf85-b6258f3652be",
                         "email":"new@example.com","displayName":"new operator",
                         "temporaryPassword":"%s","roles":["AUDIT_READER"],
                         "directPermissions":["AUDIT_READ"],"reason":"ACCOUNT_PROVISIONING"}
                        """.formatted(temporaryPassword)));
    }

    private class PrincipalResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == PlatformOperatorPrincipal.class
                    && parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                org.springframework.web.bind.support.WebDataBinderFactory binderFactory
        ) {
            return principal;
        }
    }
}
