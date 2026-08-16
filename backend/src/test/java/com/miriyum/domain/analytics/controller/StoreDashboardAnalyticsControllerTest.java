package com.miriyum.domain.analytics.controller;

import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.COMPLETE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.PARTIAL;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.UNAVAILABLE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode.SOURCE_CONTRACT_MISSING;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.analytics.config.AnalyticsSecurityConfig;
import com.miriyum.domain.analytics.controller.storeoperator.StoreDashboardAnalyticsController;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.CountMetricResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricsResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricMetadata;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.NoShowMetricResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.NoShowValue;
import com.miriyum.domain.analytics.service.StoreDashboardAnalyticsService;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreDashboardAnalyticsController.class)
@Import({AnalyticsSecurityConfig.class, GlobalExceptionHandler.class})
class StoreDashboardAnalyticsControllerTest {

    private static final String PATH =
            "/api/v1/store-operators/stores/17/dashboard-statistics";
    private static final Instant AS_OF = Instant.parse("2026-08-16T09:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean StoreDashboardAnalyticsService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void missingBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        then(service).shouldHaveNoInteractions();
    }

    @Test
    void ownedStoreReturnsPartialReservationNoShowWithoutZeroMasquerade() throws Exception {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 41L));
        given(service.getDashboard(41L, 17L)).willReturn(snapshot());

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.metrics.noShow.completeness").value("PARTIAL"))
                .andExpect(jsonPath(
                        "$.data.metrics.noShow.value.reservationConfirmed.value").isEmpty())
                .andExpect(jsonPath(
                        "$.data.metrics.noShow.value.waitingConfirmed.value").value(2));
    }

    @Test
    void foreignStoreReturnsForbiddenWithoutMetricData() throws Exception {
        authenticate();
        given(service.getDashboard(41L, 17L))
                .willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED));

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_003"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void missingStoreReturnsNotFoundWithoutMetricData() throws Exception {
        authenticate();
        given(service.getDashboard(41L, 17L))
                .willThrow(new ServiceException(StoreErrorCode.STORE_NOT_FOUND));

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private void authenticate() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 41L));
    }

    private static DashboardSnapshotResponse snapshot() {
        MetricMetadata missing = new MetricMetadata(
                "ANALYTICS-004-v1", 1, AS_OF, null, null,
                UNAVAILABLE, false, SOURCE_CONTRACT_MISSING);
        MetricMetadata waiting = new MetricMetadata(
                "ANALYTICS-004-waiting-v1", 2, AS_OF, AS_OF, "checkpoint",
                COMPLETE, false, null);
        MetricMetadata partial = new MetricMetadata(
                "ANALYTICS-004-v1", 2, AS_OF, AS_OF, "checkpoint",
                PARTIAL, false, SOURCE_CONTRACT_MISSING);
        NoShowMetricResponse noShow = new NoShowMetricResponse(
                new NoShowValue(
                        new CountMetricResponse(null, missing),
                        new CountMetricResponse(null, missing),
                        new CountMetricResponse(2L, waiting)),
                partial);
        return new DashboardSnapshotResponse(
                UUID.randomUUID(), "17", LocalDate.of(2026, 8, 16),
                "Asia/Seoul", AS_OF, AS_OF, 1,
                new DashboardMetricsResponse(null, null, null, null, null, noShow));
    }
}
