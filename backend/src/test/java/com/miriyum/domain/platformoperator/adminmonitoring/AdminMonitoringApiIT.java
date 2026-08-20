package com.miriyum.domain.platformoperator.adminmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CasePage;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Completeness;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringQueryService;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringAuthorizationService;
import com.miriyum.domain.platformoperator.config.PlatformOperatorSecurityConfig;
import com.miriyum.domain.platformoperator.controller.management.adminmonitoring.AdminMonitoringController;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuthService;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@Tag("integration-shard-a")
@WebMvcTest(
        controllers = AdminMonitoringController.class,
        properties = {
                "miriyum.platform-operator.enabled=true",
                "miriyum.admin-monitoring.enabled=true"
        })
@Import({SecurityConfig.class, PlatformOperatorSecurityConfig.class, GlobalExceptionHandler.class})
class AdminMonitoringApiIT {

    private static final String LIST = "/api/v1/platform-operators/monitoring-cases"
            + "?changedFrom=2026-08-19T10:00:00Z&changedTo=2026-08-20T10:00:00Z";
    private static final String DETAIL = "/api/v1/platform-operators/monitoring-cases"
            + "/RESERVATION/reservation-hold:12?asOf=2026-08-20T10:00:00Z";
    private static final PlatformOperatorPrincipal PRINCIPAL = new PlatformOperatorPrincipal(
            17L, "operator@example.com", "session", 3L, 2L, false);

    @Autowired MockMvc mvc;
    @MockitoBean AdminMonitoringQueryService queries;
    @MockitoBean PlatformOperatorAuthService auth;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void missingPlatformOperatorJwtIsUnauthorized() throws Exception {
        mvc.perform(get(LIST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        then(queries).shouldHaveNoInteractions();
    }

    @Test
    void currentSessionCanReadWhileStaleAuthorityVersionIsUnauthorized() throws Exception {
        authenticate();
        given(queries.list(any(), any())).willReturn(new CasePage(
                List.of(), Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                Completeness.COMPLETE, List.of(), null));

        mvc.perform(get(LIST).header(HttpHeaders.AUTHORIZATION, "Bearer operator-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        given(queries.list(any(), any())).willThrow(
                new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
        mvc.perform(get(LIST).header(HttpHeaders.AUTHORIZATION, "Bearer operator-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_015"));
    }

    @Test
    void missingReadPermissionOrDetailAssignmentIsForbidden() throws Exception {
        authenticate();
        given(queries.list(any(), any())).willThrow(
                new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
        mvc.perform(get(LIST).header(HttpHeaders.AUTHORIZATION, "Bearer operator-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_001"));

        given(queries.get(any(), any(), any(), any())).willThrow(
                new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
        mvc.perform(get(DETAIL).header(HttpHeaders.AUTHORIZATION, "Bearer operator-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_001"));
    }

    @Test
    void featureOffDoesNotRegisterTheMonitoringController() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "miriyum.platform-operator.enabled=true",
                        "miriyum.admin-monitoring.enabled=false")
                .withBean(AdminMonitoringQueryService.class, () -> queries)
                .withUserConfiguration(AdminMonitoringController.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(AdminMonitoringController.class));
    }

    @Test
    void platformOperatorOffDoesNotCreateMonitoringRuntimeBeans() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "miriyum.platform-operator.enabled=false",
                        "miriyum.admin-monitoring.enabled=true")
                .withBean(OperatorAuthorityReader.class,
                        () -> org.mockito.Mockito.mock(OperatorAuthorityReader.class))
                .withBean(AdminCaseAssignmentVerifier.class,
                        () -> org.mockito.Mockito.mock(AdminCaseAssignmentVerifier.class))
                .withUserConfiguration(AdminMonitoringAuthorizationService.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(AdminMonitoringAuthorizationService.class));
    }

    private void authenticate() {
        given(auth.authenticateAccess("operator-token")).willReturn(PRINCIPAL);
    }
}
