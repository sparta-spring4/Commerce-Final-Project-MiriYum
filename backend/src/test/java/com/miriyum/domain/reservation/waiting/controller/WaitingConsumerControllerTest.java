package com.miriyum.domain.reservation.waiting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.reservation.config.ReservationSecurityConfig;
import com.miriyum.domain.reservation.waiting.controller.consumer.WaitingConsumerController;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingReceptionAvailability;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts.Snapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.InvitationCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.InvitationSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.PartyCommandResult;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession.AccuracyCategory;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession.ResultCategory;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerCommandFacade;
import com.miriyum.domain.reservation.waiting.service.WaitingConsumerQueryService;
import com.miriyum.domain.reservation.waiting.service.WaitingLocationProofService;
import com.miriyum.domain.reservation.waiting.service.WaitingPartyService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(WaitingConsumerController.class)
@Import({ReservationSecurityConfig.class, GlobalExceptionHandler.class})
class WaitingConsumerControllerTest {

    private static final String KEY = "550e8400-e29b-41d4-a716-446655440301";
    private static final UUID LOCATION_PROOF_ID =
            UUID.fromString("d276a024-71f5-4f98-9682-89f16df4fbd0");

    @Autowired MockMvc mockMvc;
    @MockitoBean WaitingConsumerQueryService queryService;
    @MockitoBean WaitingConsumerCommandFacade commandFacade;
    @MockitoBean WaitingLocationProofService locationProofService;
    @MockitoBean WaitingPartyService partyService;
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
                eq(LOCATION_PROOF_ID),
                argThat(key -> KEY.equals(key.value()))))
                .willReturn(new WaitingConsumerCommandResult(200, waiting));
        given(queryService.getCurrent(200L)).willReturn(waiting);
        given(commandFacade.cancel(
                eq(200L), eq(300L), argThat(key -> KEY.equals(key.value())),
                eq(new WaitingTeamTransitionRequest(0L))))
                .willReturn(new WaitingConsumerCommandResult(200, cancelled));

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
                                {"businessDate":"2026-08-17","partySize":2,
                                 "locationProofSessionId":"d276a024-71f5-4f98-9682-89f16df4fbd0"}
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
                eq(LOCATION_PROOF_ID),
                argThat(key -> KEY.equals(key.value())));
    }

    @Test
    void missingTokenAndStoreOperatorTokenCannotCallConsumerCommands() throws Exception {
        mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-teams")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"2026-08-17\",\"partySize\":2,"
                                + "\"locationProofSessionId\":\"d276a024-71f5-4f98-9682-89f16df4fbd0\"}"))
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
    void consumerCanIssueLocationProofWithoutIdempotencyOrRawLocationResponse() throws Exception {
        authenticateConsumer(200L);
        UUID proofId = UUID.fromString("d276a024-71f5-4f98-9682-89f16df4fbd0");
        given(locationProofService.issue(eq(200L), eq(100L), any()))
                .willReturn(new Snapshot(
                        proofId, ResultCategory.VERIFIED, AccuracyCategory.ACCEPTABLE,
                        "WAITING_LOCATION_V1", 4L,
                        Instant.parse("2026-08-19T03:00:00Z"),
                        Instant.parse("2026-08-19T03:00:00Z"),
                        Instant.parse("2026-08-19T03:02:00Z")));

        mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-location-proofs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "measurementStatus":"MEASURED",
                                  "latitude":37.123456789012345,
                                  "longitude":127.987654321098765,
                                  "accuracyMeters":73.25,
                                  "measuredAt":"2026-08-19T02:59:50Z",
                                  "integrityStatus":"CLEAR"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proofSessionId").value(proofId.toString()))
                .andExpect(jsonPath("$.data.resultCategory").value("VERIFIED"))
                .andExpect(jsonPath("$.data.policyVersion").value("WAITING_LOCATION_V1"))
                .andExpect(jsonPath("$.data.latitude").doesNotExist())
                .andExpect(jsonPath("$.data.longitude").doesNotExist())
                .andExpect(jsonPath("$.data.distanceMeters").doesNotExist())
                .andExpect(jsonPath("$.data.accuracyMeters").doesNotExist())
                .andExpect(jsonPath("$.data.measuredAt").doesNotExist());
    }

    @Test
    void anonymousAndStoreOperatorCannotIssueConsumerLocationProof() throws Exception {
        String body = """
                {"measurementStatus":"PERMISSION_DENIED","integrityStatus":"CLEAR"}
                """;
        mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-location-proofs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));
        mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-location-proofs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(locationProofService).shouldHaveNoInteractions();
    }

    @Test
    void consumerCanIssueAndAcceptPartyInvitationThroughIdempotentRoutes() throws Exception {
        authenticateConsumer(200L);
        given(partyService.issueInvitation(eq(200L), eq(300L), any(), any()))
                .willReturn(new InvitationCommandResult(200, new InvitationSnapshot(
                        "701", Instant.parse("2026-08-19T03:15:00Z"), "fresh-code")));
        WaitingConsumerSnapshot joined = snapshot(WaitingTeamStatus.WAITING, 1L);
        given(partyService.acceptInvitation(eq(200L), any(), any()))
                .willReturn(new PartyCommandResult(200, joined));

        mockMvc.perform(post("/api/v1/consumers/me/waiting-teams/300/invitations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitationId").value("701"))
                .andExpect(jsonPath("$.data.invitationCode").value("fresh-code"));

        mockMvc.perform(post("/api/v1/consumers/me/waiting-invitation-acceptances")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invitationCode\":\"fresh-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waitingTeamId").value("300"));
    }

    @Test
    void partyMutationRoutesRequireConsumerAuthenticationAndIdempotency() throws Exception {
        mockMvc.perform(post("/api/v1/consumers/me/waiting-teams/300/membership-departures")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized());

        authenticateConsumer(200L);
        mockMvc.perform(post("/api/v1/consumers/me/waiting-teams/300/memberships/402/removals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        then(partyService).shouldHaveNoInteractions();
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeEnteringConsumerBusinessLogic() throws Exception {
        authenticateConsumer(200L);

        mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-teams")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"2026-08-17\",\"partySize\":2,"
                                + "\"locationProofSessionId\":\"d276a024-71f5-4f98-9682-89f16df4fbd0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        then(commandFacade).shouldHaveNoInteractions();
        then(queryService).shouldHaveNoInteractions();
    }

    @Test
    void createReplayReturnsTheExactFirstHttpBodyWithoutASecondSnapshotQuery() throws Exception {
        authenticateConsumer(200L);
        WaitingConsumerSnapshot firstSnapshot = snapshot(WaitingTeamStatus.WAITING, 0L);
        given(commandFacade.create(
                eq(100L), eq(200L), eq(LocalDate.of(2026, 8, 17)), eq(2),
                eq(LOCATION_PROOF_ID),
                argThat(key -> KEY.equals(key.value()))))
                .willReturn(new WaitingConsumerCommandResult(200, firstSnapshot));

        MvcResult first = performCreate();
        MvcResult replay = performCreate();

        assertThat(replay.getResponse().getStatus())
                .isEqualTo(first.getResponse().getStatus());
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void cancellationReplayReturnsTheExactFirstHttpBodyAfterTheQueueChanges() throws Exception {
        authenticateConsumer(200L);
        WaitingConsumerSnapshot firstSnapshot = snapshot(WaitingTeamStatus.CANCELLED, 1L);
        WaitingConsumerSnapshot recalculatedSnapshot = new WaitingConsumerSnapshot(
                "300", "100", LocalDate.of(2026, 8, 17), WaitingTeamStatus.CANCELLED,
                3L, 0L, 2, Instant.parse("2026-08-17T00:00:00Z"), null, null, null,
                Instant.parse("2026-08-17T00:01:00Z"), 1L);
        given(commandFacade.cancel(
                eq(200L), eq(300L), argThat(key -> KEY.equals(key.value())),
                eq(new WaitingTeamTransitionRequest(0L))))
                .willReturn(new WaitingConsumerCommandResult(200, firstSnapshot));
        lenient().when(queryService.getOwned(200L, 300L))
                .thenReturn(firstSnapshot, recalculatedSnapshot);

        MvcResult first = performCancel();
        MvcResult replay = performCancel();

        assertThat(replay.getResponse().getStatus())
                .isEqualTo(first.getResponse().getStatus());
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    private MvcResult performCreate() throws Exception {
        return mockMvc.perform(post("/api/v1/consumers/me/stores/100/waiting-teams")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDate":"2026-08-17","partySize":2,
                                 "locationProofSessionId":"d276a024-71f5-4f98-9682-89f16df4fbd0"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult performCancel() throws Exception {
        return mockMvc.perform(post("/api/v1/consumers/me/waiting-teams/300/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andReturn();
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

}
