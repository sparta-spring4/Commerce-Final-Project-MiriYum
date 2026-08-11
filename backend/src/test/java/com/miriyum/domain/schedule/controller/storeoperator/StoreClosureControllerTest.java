package com.miriyum.domain.schedule.controller.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.schedule.closure.dto.storeoperator.RegularClosureResponse;
import com.miriyum.domain.schedule.closure.dto.storeoperator.TemporaryClosureEndAtRequest;
import com.miriyum.domain.schedule.closure.dto.storeoperator.TemporaryClosureResponse;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureReason;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureStatus;
import com.miriyum.domain.schedule.closure.service.StoreClosureCommandFacade;
import com.miriyum.domain.store.config.StoreManagementSecurityConfig;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.schedule.service.ScheduleCommandResult;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreClosureController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class StoreClosureControllerTest {
    private static final String BASE = "/api/v1/store-operator/stores/7";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";
    @Autowired MockMvc mockMvc;
    @MockitoBean StoreClosureCommandFacade service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test void missingTokenRejectsClosureCommand() throws Exception {
        mockMvc.perform(put(BASE + "/regular-closures").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"weeklyDays\":[],\"dates\":[]}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test void createsExplicitEmptyRegularClosureDraft() throws Exception {
        authenticate();
        given(service.createRegularDraft(eq(11L), eq(7L), any(), any())).willReturn(
                new ScheduleCommandResult<>(200, new RegularClosureResponse(
                        1, ScheduleVersionStatus.DRAFT, "Asia/Seoul", null, null, List.of(), List.of())));
        mockMvc.perform(put(BASE + "/regular-closures").header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"weeklyDays\":[],\"dates\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.weeklyDays").isEmpty());
    }

    @Test void createsTemporaryClosureUsingOffsetTimestamps() throws Exception {
        authenticate();
        Instant start = Instant.parse("2026-08-03T09:00:00Z");
        given(service.createTemporary(eq(11L), eq(7L), any(), any())).willReturn(
                new ScheduleCommandResult<>(200, new TemporaryClosureResponse(3, 7, start,
                        start.plusSeconds(3600), "Asia/Seoul", TemporaryClosureReason.MAINTENANCE,
                        "정비", TemporaryClosureStatus.SCHEDULED, 1)));
        mockMvc.perform(post(BASE + "/temporary-closures").header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("""
                        {"startAt":"2026-08-03T18:00:00+09:00","endAt":"2026-08-03T19:00:00+09:00",
                         "reason":"MAINTENANCE","publicMessage":"정비"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.closureId").value(3))
                .andExpect(jsonPath("$.data.startAt").value("2026-08-03T09:00:00Z"));
    }

    @Test void rejectsTemporaryClosureEndChangeWithoutReason() throws Exception {
        authenticate();

        mockMvc.perform(put(BASE + "/temporary-closures/3/end-at")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endAt\":\"2026-08-03T20:00:00+09:00\"}"))
                .andExpect(status().isBadRequest());

        then(service).shouldHaveNoInteractions();
    }

    @Test void acceptsAndForwardsTemporaryClosureEndChangeReason() throws Exception {
        authenticate();
        Instant start = Instant.parse("2026-08-03T09:00:00Z");
        given(service.changeTemporaryEnd(eq(11L), eq(7L), eq(3L), any(), any()))
                .willReturn(new ScheduleCommandResult<>(200, new TemporaryClosureResponse(
                        3, 7, start, start.plusSeconds(7200), "Asia/Seoul",
                        TemporaryClosureReason.MAINTENANCE, "정비",
                        TemporaryClosureStatus.SCHEDULED, 2)));

        mockMvc.perform(put(BASE + "/temporary-closures/3/end-at")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":"2026-08-03T20:00:00+09:00",
                                 "changeReason":"정비 연장"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<TemporaryClosureEndAtRequest> request =
                ArgumentCaptor.forClass(TemporaryClosureEndAtRequest.class);
        then(service).should().changeTemporaryEnd(eq(11L), eq(7L), eq(3L), any(), request.capture());
        assertThat(request.getValue().changeReason()).isEqualTo("정비 연장");
    }

    private void authenticate() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 11L));
    }
}
