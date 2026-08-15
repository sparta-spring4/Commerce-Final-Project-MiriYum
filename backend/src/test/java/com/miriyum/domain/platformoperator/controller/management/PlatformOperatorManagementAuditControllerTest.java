package com.miriyum.domain.platformoperator.controller.management;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.platformoperator.controller.audit.PlatformOperatorAuditController;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditService;
import com.miriyum.domain.platformoperator.service.PlatformOperatorManagementService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotentOutcome;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.ObjectMapper;

class PlatformOperatorManagementAuditControllerTest {

    private MockMvc mvc;
    private PlatformOperatorManagementService managementService;
    private PlatformOperatorPrincipal principal;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        managementService = mock(PlatformOperatorManagementService.class);
        PlatformOperatorAuditService auditService = mock(PlatformOperatorAuditService.class);
        principal = new PlatformOperatorPrincipal(
                1L, "super@example.com", "session-1", 3L, 3L, false);
        objectMapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(
                        new PlatformOperatorManagementController(managementService),
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
    void auditSearchRequiresStructuredReasonHeader() throws Exception {
        mvc.perform(get("/api/v1/platform-operators/audit-events")
                        .header("X-Admin-Case-Id", "audit-review-1")
                        .header("X-Admin-Case-Version", "1"))
                .andExpect(status().isBadRequest());
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
