package com.miriyum.domain.reservation.waiting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.reservation.config.ReservationSecurityConfig;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.controller.storeoperator.WaitingStoreOperatorController;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureJobSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJobStatus;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamListItem;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamPage;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.service.WaitingCommandFacade;
import com.miriyum.domain.reservation.waiting.service.WaitingClosureService;
import com.miriyum.domain.reservation.waiting.service.WaitingTeamQueryService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WaitingStoreOperatorController.class)
@Import({ReservationSecurityConfig.class, GlobalExceptionHandler.class})
class WaitingStoreOperatorControllerTest {

    private static final String BASE = "/api/v1/store-operators/stores/22/waiting-teams";
    private static final String DETAIL = BASE + "/77";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaitingTeamQueryService queryService;

    @MockitoBean
    private WaitingCommandFacade commandFacade;

    @MockitoBean
    private WaitingClosureService closureService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("매장 운영자 웨이팅 목록은 FIFO cursor 페이지와 안전한 필드만 반환한다")
    void returnsPrivacySafeWaitingPage() throws Exception {
        authenticateStoreOperator();
        given(queryService.getTeams(eq(33L), eq(22L), any()))
                .willReturn(new WaitingTeamPage(
                        List.of(new WaitingTeamListItem(
                                "77", WaitingTeamStatus.WAITING, 4L, 3,
                                Instant.parse("2026-08-12T03:00:00Z"), 0L)),
                        "opaque-next"));

        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .param("status", "WAITING")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].waitingTeamId").value("77"))
                .andExpect(jsonPath("$.data.items[0].queueSequence").value(4))
                .andExpect(jsonPath("$.data.nextCursor").value("opaque-next"))
                .andExpect(jsonPath("$.data.items[0].consumerAccountId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].coordinates").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].actorId").doesNotExist());
    }

    @Test
    @DisplayName("상세 조회는 멱등 헤더 없이 상태 시각과 공개 ID만 반환한다")
    void returnsWaitingDetailWithoutIdempotencyHeader() throws Exception {
        authenticateStoreOperator();
        given(queryService.getTeam(33L, 22L, 77L)).willReturn(snapshot());

        mockMvc.perform(get(DETAIL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waitingTeamId").value("77"))
                .andExpect(jsonPath("$.data.storeId").value("22"))
                .andExpect(jsonPath("$.data.consumerAccountId").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
    }

    @Test
    void returnsPrivacySafeClosureJobWithoutIdempotencyHeader() throws Exception {
        authenticateStoreOperator();
        given(closureService.getClosureJob(33L, 22L, 91L)).willReturn(new WaitingClosureJobSnapshot(
                "91", "22", WaitingClosureJobStatus.PROCESSING, 3, 1, 0, 0,
                Instant.parse("2026-08-12T08:00:00Z"), null));

        mockMvc.perform(get("/api/v1/store-operators/stores/22/waiting-closure-jobs/91")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value("91"))
                .andExpect(jsonPath("$.data.storeId").value("22"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.totalTeamCount").value(3))
                .andExpect(jsonPath("$.data.consumerAccountId").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
    }

    @Test
    void closureJobCrossStoreIsPrivacySafeNotFound() throws Exception {
        authenticateStoreOperator();
        given(closureService.getClosureJob(33L, 22L, 91L))
                .willThrow(new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND));
        mockMvc.perform(get("/api/v1/store-operators/stores/22/waiting-closure-jobs/91")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_004"));
    }

    @Test
    void closureJobRouteRejectsMissingWrongTokenAndUnsupportedMethod() throws Exception {
        String route = "/api/v1/store-operators/stores/22/waiting-closure-jobs/91";
        mockMvc.perform(get(route)).andExpect(status().isUnauthorized());
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 33L));
        mockMvc.perform(get(route).header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
        authenticateStoreOperator();
        mockMvc.perform(patch(route).header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));
    }

    @Test
    @DisplayName("네 운영자 명령은 멱등 키와 expectedVersion을 전달한다")
    void executesAllTransitionEndpoints() throws Exception {
        authenticateStoreOperator();
        given(commandFacade.call(eq(33L), eq(22L), eq(77L), any(), any()))
                .willReturn(new WaitingCommandResult(200, snapshot()));
        given(commandFacade.arrive(eq(33L), eq(22L), eq(77L), any(), any()))
                .willReturn(new WaitingCommandResult(200, snapshot()));
        given(commandFacade.checkIn(eq(33L), eq(22L), eq(77L), any(), any()))
                .willReturn(new WaitingCommandResult(200, snapshot()));
        given(commandFacade.cancel(eq(33L), eq(22L), eq(77L), any(), any()))
                .willReturn(new WaitingCommandResult(200, snapshot()));

        for (String action : List.of("calls", "arrivals", "check-ins", "cancellations")) {
            mockMvc.perform(post(DETAIL + "/" + action)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                            .header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.waitingTeamId").value("77"));
        }
    }

    @Test
    @DisplayName("인증이 없거나 consumer namespace이면 운영자 경계를 통과하지 못한다")
    void rejectsMissingAndWrongNamespaceToken() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 33L));
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
        then(queryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("누락 또는 잘못된 멱등 키는 업무 Service 진입 전에 거절한다")
    void rejectsMissingAndMalformedIdempotencyKey() throws Exception {
        authenticateStoreOperator();
        mockMvc.perform(post(DETAIL + "/calls")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
        mockMvc.perform(post(DETAIL + "/calls")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_004"));
        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("expectedVersion 누락과 음수는 COMMON_001로 거절한다")
    void rejectsInvalidExpectedVersion() throws Exception {
        authenticateStoreOperator();
        for (String body : List.of("{}", "{\"expectedVersion\":-1}")) {
            mockMvc.perform(post(DETAIL + "/cancellations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                            .header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"));
        }
        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("잘못된 cursor와 size 범위는 COMMON_001로 거절한다")
    void rejectsMalformedCursorAndSizeBounds() throws Exception {
        authenticateStoreOperator();
        for (String url : List.of(
                BASE + "?cursor=not-a-cursor",
                BASE + "?status=UNKNOWN",
                BASE + "?size=0",
                BASE + "?size=101")) {
            mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"));
        }
        then(queryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("타 매장 팀은 개인정보 안전한 WAITING_003으로 응답한다")
    void returnsPrivacySafeNotFoundForDifferentStore() throws Exception {
        authenticateStoreOperator();
        given(queryService.getTeam(33L, 22L, 77L))
                .willThrow(new ServiceException(ReservationErrorCode.WAITING_TEAM_NOT_FOUND));

        mockMvc.perform(get(DETAIL).header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_003"));
    }

    @ParameterizedTest
    @MethodSource("commandErrors")
    @DisplayName("명령 오류는 canonical HTTP 상태와 code를 변경하지 않고 반환한다")
    void returnsExactCommandErrorMapping(ErrorCode errorCode, int expectedStatus) throws Exception {
        authenticateStoreOperator();
        given(commandFacade.call(eq(33L), eq(22L), eq(77L), any(), any()))
                .willThrow(new ServiceException(errorCode));

        mockMvc.perform(post(DETAIL + "/calls")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    }

    @Test
    @DisplayName("웨이팅 경로군의 미승인 method와 하위 path는 fail closed 한다")
    void deniesUnsupportedMethodsAndPaths() throws Exception {
        authenticateStoreOperator();
        mockMvc.perform(patch(DETAIL).header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));
        mockMvc.perform(post(DETAIL + "/unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));
    }

    private void authenticateStoreOperator() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));
    }

    private static Stream<Arguments> commandErrors() {
        return Stream.of(
                Arguments.of(ReservationErrorCode.WAITING_TEAM_NOT_FOUND, 404),
                Arguments.of(ReservationErrorCode.WAITING_VERSION_CONFLICT, 409),
                Arguments.of(ReservationErrorCode.WAITING_INVALID_TRANSITION, 409),
                Arguments.of(ReservationErrorCode.WAITING_NOT_FIFO_HEAD, 409),
                Arguments.of(ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT, 409),
                Arguments.of(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, 409),
                Arguments.of(CommonErrorCode.CONCURRENT_MODIFICATION, 409));
    }

    private WaitingTeamSnapshot snapshot() {
        return new WaitingTeamSnapshot(
                "77", "22", WaitingTeamStatus.WAITING, 4L, 3,
                Instant.parse("2026-08-12T03:00:00Z"), null, null, null, null, 0L);
    }
}
