package com.miriyum.domain.platformoperator.controller.management.adminmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringRequests.ListQuery;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseDetail;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CasePage;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Completeness;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.MaskingLevel;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Transition;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class AdminMonitoringControllerTest {

    private static final Instant AS_OF = Instant.parse("2026-08-20T10:00:00Z");
    private static final PlatformOperatorPrincipal PRINCIPAL = new PlatformOperatorPrincipal(
            17L, "operator@example.com", "session", 3L, 2L, false);

    private AdminMonitoringQueryService queries;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queries = mock(AdminMonitoringQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminMonitoringController(queries))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PrincipalResolver())
                .build();
    }

    @Test
    void bindsQualifiedFiltersAndReturnsTheSuccessEnvelope() throws Exception {
        given(queries.list(any(), any())).willReturn(new CasePage(
                List.of(), AS_OF, AS_OF, Completeness.COMPLETE, List.of(), null));

        mvc.perform(get("/api/v1/platform-operators/monitoring-cases")
                        .queryParam("storeId", "7")
                        .queryParam("caseTypes", "RESERVATION", "WAITING")
                        .queryParam("lifecycleStatuses", "CONFIRMED")
                        .queryParam("sourceStatuses", "RESERVATION:CONFIRMED", "PAYMENT:PAID")
                        .queryParam("reconciliationStatuses", "MATCHED")
                        .queryParam("changedFrom", "2026-08-19T10:00:00Z")
                        .queryParam("changedTo", "2026-08-20T10:00:00Z")
                        .queryParam("size", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.asOf").value("2026-08-20T10:00:00Z"))
                .andExpect(jsonPath("$.data.items").isArray());

        ArgumentCaptor<ListQuery> query = ArgumentCaptor.forClass(ListQuery.class);
        then(queries).should().list(org.mockito.ArgumentMatchers.eq(PRINCIPAL), query.capture());
        assertThat(query.getValue().canonicalFilter())
                .contains("caseTypes=RESERVATION,WAITING")
                .contains("sourceStatuses=PAYMENT:PAID,RESERVATION:CONFIRMED")
                .endsWith("size=40");
    }

    @Test
    void mapsInvalidMonitoringRangeToThePublicFilterError() throws Exception {
        mvc.perform(get("/api/v1/platform-operators/monitoring-cases")
                        .queryParam("changedFrom", "2026-08-20T10:00:00Z")
                        .queryParam("changedTo", "2026-08-19T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITORING_001"));

        verifyNoInteractions(queries);
    }

    @Test
    void bindsDetailCaseAndOptionalAsOf() throws Exception {
        given(queries.get(any(), any(), any(), any())).willReturn(null);

        mvc.perform(get("/api/v1/platform-operators/monitoring-cases/{caseType}/{caseId}",
                        "RESERVATION", "reservation-hold:12")
                        .queryParam("asOf", "2026-08-20T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        then(queries).should().get(PRINCIPAL, CaseType.RESERVATION, "reservation-hold:12", AS_OF);
    }

    @Test
    void serializesTransitionEventTypeDeclaredByThePublicContract() throws Exception {
        given(queries.get(any(), any(), any(), any())).willReturn(new CaseDetail(
                CaseType.RESERVATION,
                "reservation-hold:12",
                "7",
                LifecycleStatus.CHECKED_IN,
                2L,
                AS_OF,
                AS_OF,
                Completeness.COMPLETE,
                MaskingLevel.MINIMIZED,
                2,
                AS_OF.plusSeconds(3600),
                AS_OF.plusSeconds(7200),
                null,
                List.of(),
                List.of(new Transition(
                        Source.RESERVATION,
                        "CHECKED_IN",
                        1L,
                        "CONFIRMED",
                        "CONFIRMED",
                        AS_OF.minusSeconds(1))),
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                List.of()));

        mvc.perform(get("/api/v1/platform-operators/monitoring-cases/{caseType}/{caseId}",
                        "RESERVATION", "reservation-hold:12")
                        .queryParam("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history[0].eventType").value("CHECKED_IN"));
    }

    private static class PrincipalResolver implements HandlerMethodArgumentResolver {
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
            return PRINCIPAL;
        }
    }
}
