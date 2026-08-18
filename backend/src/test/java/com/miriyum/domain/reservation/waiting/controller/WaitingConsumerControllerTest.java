package com.miriyum.domain.reservation.waiting.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.reservation.config.ReservationSecurityConfig;
import com.miriyum.domain.reservation.waiting.controller.consumer.WaitingConsumerController;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingReceptionAvailability;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerCommandFacade;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerQueryService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WaitingConsumerController.class)
@Import({ReservationSecurityConfig.class, GlobalExceptionHandler.class})
class WaitingConsumerControllerTest {

    private static final String KEY = "550e8400-e29b-41d4-a716-446655440301";

    @Autowired MockMvc mockMvc;
    @MockitoBean WaitingConsumerQueryService queryService;
    @MockitoBean WaitingConsumerCommandFacade commandFacade;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void consumerCanInspectRegisterReadCurrentAndCancelWithoutInternalFields() throws Exception {
        authenticateConsumer(200L);
        WaitingConsumerSnapshot waiting = snapshot(WaitingTeamStatus.WAITING, 0L);
        WaitingConsumerSnapshot cancelled = snapshot(WaitingTeamStatus.CANCELLED, 1L);
        given(queryService.getAvailability(200L, 100L)).willReturn(
                WaitingReceptionAvailability.open(100L, LocalDate.of(2026, 8, 17)));
        given(commandFacade.create(
                eq(100L), eq(200L), eq(LocalDate.of(2026, 8, 17)), eq(2),
                argThat(key -> KEY.equals(key.value()))))
                .willReturn(new WaitingCommandResult(200, teamSnapshot()));
        given(queryService.getOwned(200L, 300L)).willReturn(waiting, cancelled);
        given(queryService.getCurrent(200L)).willReturn(waiting);
        given(commandFacade.cancel(
                eq(200L), eq(300L), argThat(key -> KEY.equals(key.value())),
                eq(new WaitingTeamTransitionRequest(0L))))
                .willReturn(new WaitingCommandResult(200, teamSnapshot()));

        mockMvc.perform(get("/api/v1/consumers/me/stores/100/waiting-availabilities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepting").value(true))
                .andExpect(jsonPath("$.data.businessDate").value("2026-08-17"));

        mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-teams")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDate":"2026-08-17","partySize":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waitingTeamId").value("300"))
                .andExpect(jsonPath("$.data.teamsAhead").value(2))
                .andExpect(jsonPath("$.data.consumerAccountId").doesNotExist());

        mockMvc.perform(get("/api/v1/consumers/me/waiting-teams/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"));

        mockMvc.perform(post("/api/v1/consumers/me/waiting-teams/300/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        then(commandFacade).should().create(
                eq(100L), eq(200L), eq(LocalDate.of(2026, 8, 17)), eq(2),
                argThat(key -> KEY.equals(key.value())));
    }

    @Test
    void missingTokenAndStoreOperatorTokenCannotCallConsumerCommands() throws Exception {
        mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-teams")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"2026-08-17\",\"partySize\":2}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));
        mockMvc.perform(post("/api/v1/consumers/me/waiting-teams/300/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeEnteringConsumerBusinessLogic() throws Exception {
        authenticateConsumer(200L);

        mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-teams")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"2026-08-17\",\"partySize\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        then(commandFacade).shouldHaveNoInteractions();
        then(queryService).shouldHaveNoInteractions();
    }

    private void authenticateConsumer(long accountId) {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, accountId));
    }

    private static WaitingConsumerSnapshot snapshot(WaitingTeamStatus status, long version) {
        return new WaitingConsumerSnapshot(
                "300", "100", LocalDate.of(2026, 8, 17), status, 3L, 2L, 2,
                Instant.parse("2026-08-17T00:00:00Z"), null, null, null,
                status == WaitingTeamStatus.CANCELLED
                        ? Instant.parse("2026-08-17T00:01:00Z") : null,
                version);
    }

    private static WaitingTeamSnapshot teamSnapshot() {
        return new WaitingTeamSnapshot(
                "300", "100", WaitingTeamStatus.WAITING, 3L, 2,
                Instant.parse("2026-08-17T00:00:00Z"), null, null, null, null, 0L);
    }
}
