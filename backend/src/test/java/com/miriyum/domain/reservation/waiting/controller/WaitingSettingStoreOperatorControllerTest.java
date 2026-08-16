package com.miriyum.domain.reservation.waiting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.*;
import com.miriyum.domain.reservation.config.ReservationSecurityConfig;
import com.miriyum.domain.reservation.waiting.controller.storeoperator.WaitingSettingStoreOperatorController;
import com.miriyum.domain.reservation.waiting.dto.*;
import com.miriyum.domain.reservation.waiting.entity.*;
import com.miriyum.domain.reservation.waiting.service.WaitingSettingService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WaitingSettingStoreOperatorController.class)
@Import({ReservationSecurityConfig.class, GlobalExceptionHandler.class})
class WaitingSettingStoreOperatorControllerTest {
    private static final String BASE = "/api/v1/store-operators/stores/22/waiting-settings";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440271";

    @Autowired MockMvc mockMvc;
    @MockitoBean WaitingSettingService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void getsSafeDefaults() throws Exception {
        authenticate();
        given(service.get(33L, 22L)).willReturn(new WaitingSettingSnapshot(
                22L, false, WaitingReceptionMode.PAUSED, 60, 0L));

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.receptionMode").value("PAUSED"))
                .andExpect(jsonPath("$.data.advanceOpenMinutes").value(60))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void returnsAcceptedClosureJob() throws Exception {
        authenticate();
        WaitingClosureJobSnapshot job = new WaitingClosureJobSnapshot(
                "91", "22", WaitingClosureJobStatus.PENDING, 2, 0, 0, 0,
                Instant.parse("2026-08-16T00:00:00Z"), null);
        given(service.replace(org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.eq(22L), any(), any()))
                .willReturn(new WaitingSettingCommandResult(202, job));

        mockMvc.perform(put(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"enabled":false,"receptionMode":"PAUSED",
                                 "advanceOpenMinutes":60,"disableAction":"CLOSE_ACTIVE_TEAMS"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value("91"));
    }

    @Test
    void getsDeactivationImpact() throws Exception {
        authenticate();
        given(service.inspectDeactivation(33L, 22L))
                .willReturn(new WaitingSettingDeactivationImpact(22L, 4L, 3L));

        mockMvc.perform(get(BASE + "/deactivation-impact")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.activeTeamCount").value(3));
    }

    @Test
    void rejectsMissingIdempotencyAndCrossStoreAccess() throws Exception {
        authenticate();
        mockMvc.perform(put(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"enabled":true,"receptionMode":"AUTO",
                                 "advanceOpenMinutes":60}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        given(service.get(33L, 22L)).willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED));
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    @Test
    void waitingSettingFamilyIsFailClosed() throws Exception {
        authenticate();
        mockMvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));
        mockMvc.perform(get(BASE + "/unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));
    }

    private void authenticate() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));
    }
}
