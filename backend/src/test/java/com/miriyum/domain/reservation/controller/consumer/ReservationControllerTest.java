package com.miriyum.domain.reservation.controller.consumer;

import static org.mockito.ArgumentMatchers.any;
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
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.config.ReservationSecurityConfig;
import com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequest;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.response.CustomerReservationTimeStatus;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationCheckInQrGrantResponse;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryItemResponse;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.dto.response.ReservationMenuSelectionResponse;
import com.miriyum.domain.reservation.dto.response.ReservationPartyResponse;
import com.miriyum.domain.reservation.dto.response.ReservationRequestResponse;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationCreationCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCreationCommandResult;
import com.miriyum.domain.reservation.service.ReservationCancellationCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCancellationCommandResult;
import com.miriyum.domain.reservation.service.ReservationDepositCommandResult;
import com.miriyum.domain.reservation.service.ReservationDepositProcessCommandFacade;
import com.miriyum.domain.reservation.service.ReservationDepositProcessService;
import com.miriyum.domain.reservation.service.ReservationCheckInQrGrantCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCheckInQrGrantResult;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.PageMetadata;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReservationController.class)
@Import({ReservationSecurityConfig.class, GlobalExceptionHandler.class})
class ReservationControllerTest {

    private static final String DETAIL_URL = "/api/v1/consumers/me/reservations/77";
    private static final String ROOT_URL = "/api/v1/consumers/me/reservations";
    private static final String HISTORY_URL = "/api/v1/consumers/me/reservations";
    private static final String QR_GRANT_URL = DETAIL_URL + "/check-in-qr-grants";
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String REQUEST_URL =
            "/api/v1/consumers/me/reservation-requests/901";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private ReservationCreationCommandFacade reservationCreationCommandFacade;

    @MockitoBean
    private ReservationCancellationCommandFacade reservationCancellationCommandFacade;

    @MockitoBean
    private ReservationDepositProcessCommandFacade reservationDepositProcessCommandFacade;

    @MockitoBean
    private ReservationDepositProcessService reservationDepositProcessService;

    @MockitoBean
    private ReservationCheckInQrGrantCommandFacade qrGrantFacade;

    @MockitoBean
    private ConsumerAccountService consumerAccountService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void returnsLatestOwnedDepositRequestWithoutIdempotencyKey() throws Exception {
        authenticateConsumer(11L);
        given(reservationDepositProcessService.getOwnedRequest(901L, 11L))
                .willReturn(requestResponse(
                        ReservationDepositProcessStatus.AWAITING_PAYMENT, false));

        mockMvc.perform(get(REQUEST_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationRequestId").value("901"))
                .andExpect(jsonPath("$.data.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.paymentPreparation.status").value("READY"));

        then(reservationDepositProcessService).should().getOwnedRequest(901L, 11L);
    }

    @Test
    void returnsCompletedDepositRequestWithTheFinalReservation() throws Exception {
        authenticateConsumer(11L);
        given(reservationDepositProcessService.getOwnedRequest(901L, 11L))
                .willReturn(requestResponse(
                        ReservationDepositProcessStatus.COMPLETED,
                        false,
                        detailResponse()));

        mockMvc.perform(get(REQUEST_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reservation.reservationId").value("77"))
                .andExpect(jsonPath("$.data.reservation.status").value("CONFIRMED"));
    }

    @Test
    void hidesMissingOrForeignDepositRequestBehindReservationNotFound() throws Exception {
        authenticateConsumer(11L);
        given(reservationDepositProcessService.getOwnedRequest(901L, 11L))
                .willThrow(new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));

        mockMvc.perform(get(REQUEST_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_001"));
    }

    @Test
    void finalizesDepositRequestWithTypedAcceptedResponse() throws Exception {
        authenticateConsumer(11L);
        given(reservationDepositProcessCommandFacade.finalizeRequest(
                eq(11L), eq(901L), any(IdempotencyKey.class)))
                .willReturn(ReservationDepositCommandResult.pending(requestResponse(
                        ReservationDepositProcessStatus.AWAITING_PAYMENT, false)));

        mockMvc.perform(post(REQUEST_URL + "/finalizations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("AWAITING_PAYMENT"));
    }

    @Test
    void returnsOkOnlyWhenAbandonmentHasTerminated() throws Exception {
        authenticateConsumer(11L);
        given(reservationDepositProcessCommandFacade.abandonRequest(
                eq(11L), eq(901L), any(IdempotencyKey.class)))
                .willReturn(ReservationDepositCommandResult.terminated(requestResponse(
                        ReservationDepositProcessStatus.ABANDONED, true)));

        mockMvc.perform(post(REQUEST_URL + "/abandonments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ABANDONED"))
                .andExpect(jsonPath("$.data.abandonmentRequested").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"finalizations", "abandonments"})
    void rejectsMissingIdempotencyKeyForDepositCommands(String command) throws Exception {
        authenticateConsumer(11L);

        mockMvc.perform(post(REQUEST_URL + "/" + command)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        then(reservationDepositProcessCommandFacade).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"finalizations", "abandonments"})
    void rejectsMissingDepositCommandBodyAsCommon002(String command) throws Exception {
        authenticateConsumer(11L);
        stubPendingDepositCommand(command);

        mockMvc.perform(post(REQUEST_URL + "/" + command)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(reservationDepositProcessCommandFacade).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"finalizations", "abandonments"})
    void rejectsUnknownDepositCommandBodyAsCommon002(String command) throws Exception {
        authenticateConsumer(11L);
        stubPendingDepositCommand(command);

        mockMvc.perform(post(REQUEST_URL + "/" + command)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(reservationDepositProcessCommandFacade).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/finalizations", "/abandonments"})
    void rejectsMissingAuthenticationForEveryDepositRequestRoute(String suffix)
            throws Exception {
        if (suffix.isEmpty()) {
            mockMvc.perform(get(REQUEST_URL))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_001"));
        } else {
            mockMvc.perform(post(REQUEST_URL + suffix)
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_001"));
        }

        then(reservationDepositProcessService).shouldHaveNoInteractions();
        then(reservationDepositProcessCommandFacade).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/finalizations", "/abandonments"})
    void rejectsStoreOperatorNamespaceForEveryDepositRequestRoute(String suffix)
            throws Exception {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));

        if (suffix.isEmpty()) {
            mockMvc.perform(get(REQUEST_URL)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_004"));
        } else {
            mockMvc.perform(post(REQUEST_URL + suffix)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_004"));
        }

        then(reservationDepositProcessService).shouldHaveNoInteractions();
        then(reservationDepositProcessCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    void issuesQrGrantWithoutBodyOrIdempotencyKey() throws Exception {
        authenticateConsumer(11L);
        OffsetDateTime issuedAt = OffsetDateTime.parse("2026-08-16T01:00:00Z");
        given(qrGrantFacade.issue(11L, 77L)).willReturn(new ReservationCheckInQrGrantResult(
                201,
                new ReservationCheckInQrGrantResponse(
                        "77",
                        "rqg_v1_" + "A".repeat(43),
                        3L,
                        issuedAt,
                        issuedAt.plusSeconds(30)
                )
        ));

        mockMvc.perform(post(QR_GRANT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reservationId").value("77"))
                .andExpect(jsonPath("$.data.tokenVersion").value(3));

        then(qrGrantFacade).should().issue(11L, 77L);
    }

    @Test
    void returnsAuthenticatedConsumersReservationHistory() throws Exception {
        authenticateConsumer(11L);
        ReservationHistoryPageResponse page = new ReservationHistoryPageResponse(
                List.of(new ReservationHistoryItemResponse(
                        "91", "7", "MiriYum",
                        LocalDate.of(2026, 8, 10),
                        CustomerReservationTimeStatus.RESOLVED,
                        OffsetDateTime.parse("2026-08-10T18:00:00+09:00"),
                        OffsetDateTime.parse("2026-08-10T19:00:00+09:00"),
                        "Asia/Seoul", 2, "CONFIRMED",
                        OffsetDateTime.parse("2026-08-01T00:00:00Z"))),
                new PageMetadata(0, 20, 1, 1, false));
        given(reservationService.getConsumerReservationHistory(
                eq(11L),
                eq(ReservationHistorySearchRequest.from(
                        "CONFIRMED", 0, 20, "serviceDate,asc"))))
                .willReturn(page);

        mockMvc.perform(get(HISTORY_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .queryParam("status", "CONFIRMED")
                        .queryParam("page", "0")
                        .queryParam("size", "20")
                        .queryParam("sort", "serviceDate,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reservationId").value("91"))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));

        then(consumerAccountService).should().getMe(11L);
        then(reservationService).should().getConsumerReservationHistory(
                11L,
                ReservationHistorySearchRequest.from(
                        "CONFIRMED", 0, 20, "serviceDate,asc"));
    }

    @Test
    @DisplayName("소비자 예약 내역은 NO_SHOW 상태를 조회 조건으로 전달한다")
    void acceptsNoShowReservationHistoryStatus() throws Exception {
        authenticateConsumer(11L);
        given(reservationService.getConsumerReservationHistory(
                11L, ReservationHistorySearchRequest.from("NO_SHOW", null, null, null)))
                .willReturn(new ReservationHistoryPageResponse(
                        List.of(), new PageMetadata(0, 20, 0, 0, false)));

        mockMvc.perform(get(HISTORY_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .queryParam("status", "NO_SHOW"))
                .andExpect(status().isOk());

        then(reservationService).should().getConsumerReservationHistory(
                11L, ReservationHistorySearchRequest.from("NO_SHOW", null, null, null));
    }

    @Test
    void accountStatePrecedesReservationHistoryQueryValidation() throws Exception {
        authenticateConsumer(11L);
        given(consumerAccountService.getMe(11L))
                .willThrow(new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED));

        mockMvc.perform(get(HISTORY_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .queryParam("sort", "status,asc"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_011"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    void returnsEmptyReservationHistoryPage() throws Exception {
        authenticateConsumer(11L);
        given(reservationService.getConsumerReservationHistory(
                11L, ReservationHistorySearchRequest.from(null, null, null, null)))
                .willReturn(new ReservationHistoryPageResponse(
                        List.of(), new PageMetadata(0, 20, 0, 0, false)));

        mockMvc.perform(get(HISTORY_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page.totalElements").value(0))
                .andExpect(jsonPath("$.data.page.hasNext").value(false));
    }

    @Test
    void rejectsUnsupportedReservationHistorySort() throws Exception {
        authenticateConsumer(11L);

        mockMvc.perform(get(HISTORY_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .queryParam("sort", "status,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(consumerAccountService).should().getMe(11L);
        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Access Token이 없으면 본인 예약 상세를 조회할 수 없다")
    void rejectsMissingBearerToken() throws Exception {
        // when & then
        mockMvc.perform(get(DETAIL_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("운영자 토큰으로 소비자 예약 상세를 조회할 수 없다")
    void rejectsStoreOperatorToken() throws Exception {
        // given
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));

        // when & then
        mockMvc.perform(get(DETAIL_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("본인 예약 상세를 공통 성공 봉투와 거래 스냅샷으로 반환한다")
    void returnsConsumerReservationDetailEnvelope() throws Exception {
        // given
        authenticateConsumer(11L);
        given(reservationService.getConsumerReservation(11L, 77L))
                .willReturn(detailResponse());

        // when & then
        mockMvc.perform(get(DETAIL_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("조회되었습니다."))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data.reservationId").value("77"))
                .andExpect(jsonPath("$.data.storeId").value("22"))
                .andExpect(jsonPath("$.data.storeName").value("미리윰 식당"))
                .andExpect(jsonPath("$.data.serviceDate").value("2026-08-03"))
                .andExpect(jsonPath("$.data.timeStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.data.startAt")
                        .value("2026-08-03T18:00:00+09:00"))
                .andExpect(jsonPath("$.data.serviceEndAt")
                        .value("2026-08-03T19:00:00+09:00"))
                .andExpect(jsonPath("$.data.timeZoneId").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.party.adultCount").value(2))
                .andExpect(jsonPath("$.data.party.childCount").value(1))
                .andExpect(jsonPath("$.data.party.infantCount").value(0))
                .andExpect(jsonPath("$.data.party.totalCount").value(3))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.menuSelections[0].menuId").value("91"))
                .andExpect(jsonPath("$.data.menuSelections[0].menuName")
                        .value("아메리카노"))
                .andExpect(jsonPath("$.data.menuSelections[1].menuId").value("92"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-01T09:00:00Z"))
                .andExpect(jsonPath("$.data.endTime").doesNotExist())
                .andExpect(jsonPath("$.data.occupancyEndAt").doesNotExist());

        then(reservationService).should().getConsumerReservation(11L, 77L);
    }

    @Test
    @DisplayName("예약 상세 GET은 Idempotency-Key 없이 조회한다")
    void doesNotRequireIdempotencyKeyForRead() throws Exception {
        // given
        authenticateConsumer(11L);
        given(reservationService.getConsumerReservation(11L, 77L))
                .willReturn(detailResponse());

        // when & then
        mockMvc.perform(get(DETAIL_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId").value("77"));
    }

    @Test
    @DisplayName("본인 범위에서 찾을 수 없는 예약은 숨김 404를 반환한다")
    void returnsHiddenReservationNotFound() throws Exception {
        // given
        authenticateConsumer(11L);
        given(reservationService.getConsumerReservation(11L, 77L))
                .willThrow(new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(get(DETAIL_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_001"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("0 이하 예약 ID도 검증 오류 대신 같은 숨김 404를 반환한다")
    void returnsHiddenNotFoundForNonPositiveReservationId(long reservationId) throws Exception {
        // given
        authenticateConsumer(11L);
        given(reservationService.getConsumerReservation(11L, reservationId))
                .willThrow(new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/v1/consumers/me/reservations/{reservationId}", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_001"));

        then(reservationService).should()
                .getConsumerReservation(11L, reservationId);
    }

    @Test
    @DisplayName("인증된 소비자는 예약 생성 결과를 성공 envelope로 받는다")
    void createsReservationWithConsumerPrincipalAndIdempotencyKey() throws Exception {
        // given
        authenticateConsumer(11L);
        given(reservationCreationCommandFacade.create(
                eq(11L), any(IdempotencyKey.class), any()))
                .willReturn(new ReservationCreationCommandResult(201, detailResponse()));

        // when & then
        mockMvc.perform(post(ROOT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reservationId").value("77"));

        ArgumentCaptor<com.miriyum.domain.reservation.dto.request.ReservationCreateRequest> requestCaptor =
                ArgumentCaptor.forClass(com.miriyum.domain.reservation.dto.request.ReservationCreateRequest.class);
        ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
        then(reservationCreationCommandFacade).should().create(
                eq(11L), keyCaptor.capture(), requestCaptor.capture());
        Assertions.assertThat(keyCaptor.getValue().value()).isEqualTo(IDEMPOTENCY_KEY);
        Assertions.assertThat(requestCaptor.getValue().storeId()).isEqualTo("22");
        Assertions.assertThat(requestCaptor.getValue().party().totalCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("예약금 생성은 불변 PaymentPreparation을 포함한 202를 반환한다")
    void returnsAcceptedReservationRequestWithPaymentPreparation() throws Exception {
        authenticateConsumer(11L);
        ReservationRequestResponse response = new ReservationRequestResponse(
                "901",
                ReservationDepositProcessStatus.AWAITING_PAYMENT,
                OffsetDateTime.parse("2026-08-03T18:10:00+09:00"),
                new ReservationRequestResponse.PaymentPreparationSnapshot(
                        "pay_901",
                        "portone_901",
                        "미리윰 식당 예약금",
                        4_000L,
                        "KRW",
                        OffsetDateTime.parse("2026-08-03T18:10:00+09:00"),
                        "READY"),
                false,
                null);
        given(reservationCreationCommandFacade.create(
                eq(11L), any(IdempotencyKey.class), any()))
                .willReturn(ReservationCreationCommandResult.depositRequested(response));

        mockMvc.perform(post(ROOT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.reservationRequestId").value("901"))
                .andExpect(jsonPath("$.data.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.expiresAt")
                        .value("2026-08-03T18:10:00+09:00"))
                .andExpect(jsonPath("$.data.paymentPreparation.paymentId")
                        .value("pay_901"))
                .andExpect(jsonPath("$.data.paymentPreparation.amountMinor").value(4_000))
                .andExpect(jsonPath("$.data.paymentPreparation.status").value("READY"))
                .andExpect(jsonPath("$.data.abandonmentRequested").value(false))
                .andExpect(jsonPath("$.data.reservation").isEmpty());
    }

    @Test
    @DisplayName("재전송 결과도 저장된 생성 상태를 유지한다")
    void keepsCreatedStatusForReplayResult() throws Exception {
        // given
        authenticateConsumer(11L);
        given(reservationCreationCommandFacade.create(
                eq(11L), any(IdempotencyKey.class), any()))
                .willReturn(new ReservationCreationCommandResult(201, detailResponse()));

        // when & then
        mockMvc.perform(post(ROOT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("Idempotency-Key가 없으면 생성 facade를 호출하지 않고 COMMON_003을 반환한다")
    void rejectsMissingIdempotencyKeyBeforeFacadeInvocation() throws Exception {
        // given
        authenticateConsumer(11L);

        // when & then
        mockMvc.perform(post(ROOT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        then(reservationCreationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("형식이 잘못된 Idempotency-Key는 생성 facade를 호출하지 않고 COMMON_004를 반환한다")
    void rejectsMalformedIdempotencyKeyBeforeFacadeInvocation() throws Exception {
        // given
        authenticateConsumer(11L);

        // when & then
        mockMvc.perform(post(ROOT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_004"));

        then(reservationCreationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("인증되지 않은 요청은 예약 생성 전에 AUTH_001을 반환한다")
    void rejectsUnauthenticatedReservationCreation() throws Exception {
        // when & then
        mockMvc.perform(post(ROOT_URL)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        then(reservationCreationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("매장 운영자 토큰은 예약 생성 전에 AUTH_004를 반환한다")
    void rejectsStoreOperatorReservationCreation() throws Exception {
        // given
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));

        // when & then
        mockMvc.perform(post(ROOT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(reservationCreationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("생성 facade의 ServiceException은 상태와 코드를 그대로 반환한다")
    void passesThroughReservationServiceException() throws Exception {
        // given
        authenticateConsumer(11L);
        given(reservationCreationCommandFacade.create(
                eq(11L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(ReservationErrorCode.INSUFFICIENT_CAPACITY));

        // when & then
        mockMvc.perform(post(ROOT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_003"));
    }

    @Test
    @DisplayName("존재하지 않는 매장으로 예약을 생성하면 STORE_001을 반환한다")
    void returnsStoreNotFoundWhenCreatingReservation() throws Exception {
        // given
        authenticateConsumer(11L);
        given(reservationCreationCommandFacade.create(
                eq(11L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(StoreErrorCode.STORE_NOT_FOUND));

        // when & then
        mockMvc.perform(post(ROOT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_001"));
    }

    @Test
    @DisplayName("소비자 취소는 principal과 멱등 키를 facade에 전달한다")
    void cancelsConsumerReservationWithAuthenticatedPrincipalAndIdempotencyKey() throws Exception {
        authenticateConsumer(11L);
        given(reservationCancellationCommandFacade.cancelByConsumer(
                eq(11L), eq(77L), any(IdempotencyKey.class), any(ConsumerCancellationRequest.class)))
                .willReturn(new ReservationCancellationCommandResult(200, cancelledDetailResponse("CONSUMER")));

        mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"schedule change\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledBy").value("CONSUMER"))
                .andExpect(jsonPath("$.data.cancelledAt").doesNotExist());

        ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
        ArgumentCaptor<ConsumerCancellationRequest> requestCaptor =
                ArgumentCaptor.forClass(ConsumerCancellationRequest.class);
        then(reservationCancellationCommandFacade).should().cancelByConsumer(
                eq(11L), eq(77L), keyCaptor.capture(), requestCaptor.capture());
        Assertions.assertThat(keyCaptor.getValue().value()).isEqualTo(IDEMPOTENCY_KEY);
        Assertions.assertThat(requestCaptor.getValue().reason()).isEqualTo("schedule change");
    }

    @Test
    @DisplayName("승인하지 않은 예약 하위 경로는 예약 상세 체인에서 거부한다")
    void deniesUnknownReservationSubpath() throws Exception {
        // given
        authenticateConsumer(11L);

        // when & then
        mockMvc.perform(get("/api/v1/consumers/me/reservations/77/unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    void acceptsOmittedConsumerCancellationReason() throws Exception {
        authenticateConsumer(11L);
        given(reservationCancellationCommandFacade.cancelByConsumer(
                eq(11L), eq(77L), any(IdempotencyKey.class), any(ConsumerCancellationRequest.class)))
                .willReturn(new ReservationCancellationCommandResult(200, cancelledDetailResponse("CONSUMER")));

        mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsCancellationKeyFailuresBeforeFacadeInvocation() throws Exception {
        authenticateConsumer(11L);

        mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
        mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_004"));

        then(reservationCancellationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    void rejectsInvalidConsumerCancellationBodyBeforeFacadeInvocation() throws Exception {
        authenticateConsumer(11L);

        mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(reservationCancellationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    void rejectsUnauthenticatedOrOperatorConsumerCancellation() throws Exception {
        mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));
        mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(reservationCancellationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    void passesThroughConsumerCancellationServiceExceptions() throws Exception {
        authenticateConsumer(11L);
        for (ReservationErrorCode errorCode : List.of(
                ReservationErrorCode.RESERVATION_NOT_FOUND,
                ReservationErrorCode.INVALID_STATE_TRANSITION,
                ReservationErrorCode.CANCELLATION_NOT_ALLOWED)) {
            given(reservationCancellationCommandFacade.cancelByConsumer(
                    eq(11L), eq(77L), any(IdempotencyKey.class), any(ConsumerCancellationRequest.class)))
                    .willThrow(new ServiceException(errorCode));

            mockMvc.perform(post("/api/v1/consumers/me/reservations/77/cancellations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is(errorCode == ReservationErrorCode.RESERVATION_NOT_FOUND ? 404 : 409))
                    .andExpect(jsonPath("$.code").value(errorCode.getCode()));
        }
    }

    @Test
    void deniesNonApprovedMethodOnCancellationPath() throws Exception {
        authenticateConsumer(11L);

        mockMvc.perform(get("/api/v1/consumers/me/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));

        then(reservationCancellationCommandFacade).shouldHaveNoInteractions();
    }

    private void authenticateConsumer(long accountId) {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, accountId));
    }

    private String validReservationJson() {
        return """
                {
                  "storeId": "22",
                  "serviceDate": "2026-08-03",
                  "startTime": "18:00",
                  "party": {
                    "adultCount": 2,
                    "childCount": 1,
                    "infantCount": 0
                  }
                }
                """;
    }

    private ReservationDetailResponse detailResponse() {
        return new ReservationDetailResponse(
                "77",
                "22",
                "미리윰 식당",
                LocalDate.of(2026, 8, 3),
                CustomerReservationTimeStatus.RESOLVED,
                OffsetDateTime.parse("2026-08-03T18:00:00+09:00"),
                OffsetDateTime.parse("2026-08-03T19:00:00+09:00"),
                "Asia/Seoul",
                new ReservationPartyResponse(2, 1, 0, 3),
                "CONFIRMED",
                List.of(
                        new ReservationMenuSelectionResponse(
                                "91", "아메리카노", 4_500L, 2),
                        new ReservationMenuSelectionResponse(
                                "92", "바스크 치즈케이크", 7_000L, 1)
                ),
                OffsetDateTime.parse("2026-08-01T09:00:00Z")
        );
    }

    private ReservationRequestResponse requestResponse(
            ReservationDepositProcessStatus status,
            boolean abandonmentRequested
    ) {
        return requestResponse(status, abandonmentRequested, null);
    }

    private void stubPendingDepositCommand(String command) {
        ReservationDepositCommandResult result = ReservationDepositCommandResult.pending(
                requestResponse(ReservationDepositProcessStatus.AWAITING_PAYMENT, false));
        if ("finalizations".equals(command)) {
            given(reservationDepositProcessCommandFacade.finalizeRequest(
                    eq(11L), eq(901L), any(IdempotencyKey.class)))
                    .willReturn(result);
            return;
        }
        given(reservationDepositProcessCommandFacade.abandonRequest(
                eq(11L), eq(901L), any(IdempotencyKey.class)))
                .willReturn(result);
    }

    private ReservationRequestResponse requestResponse(
            ReservationDepositProcessStatus status,
            boolean abandonmentRequested,
            ReservationDetailResponse reservation
    ) {
        return new ReservationRequestResponse(
                "901",
                status,
                OffsetDateTime.parse("2026-08-03T18:10:00+09:00"),
                new ReservationRequestResponse.PaymentPreparationSnapshot(
                        "9001",
                        "portone-9001",
                        "미리윰 식당 예약금",
                        4_000L,
                        "KRW",
                        OffsetDateTime.parse("2026-08-03T18:10:00+09:00"),
                "READY"),
                abandonmentRequested,
                reservation);
    }

    private ReservationDetailResponse cancelledDetailResponse(String cancelledBy) {
        ReservationDetailResponse detail = detailResponse();
        return new ReservationDetailResponse(
                detail.reservationId(), detail.storeId(), detail.storeName(), detail.serviceDate(),
                detail.timeStatus(), detail.startAt(), detail.serviceEndAt(), detail.timeZoneId(),
                detail.party(), "CANCELLED", detail.menuSelections(), detail.createdAt(), cancelledBy,
                "schedule change");
    }
}
