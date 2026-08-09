package com.miriyum.domain.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.miriyum.domain.reservation.config.ReservationSecurityConfig;
import com.miriyum.domain.reservation.dto.request.StoreCancellationRequest;
import com.miriyum.domain.reservation.dto.request.StoreReservationSearchRequest;
import com.miriyum.domain.reservation.dto.response.CustomerReservationTimeStatus;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationMenuSelectionResponse;
import com.miriyum.domain.reservation.dto.response.ReservationPartyResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationPageResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationSummaryResponse;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.reservation.service.ReservationCancellationCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCancellationCommandResult;
import com.miriyum.domain.store.core.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.PageMetadata;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreReservationController.class)
@Import({ReservationSecurityConfig.class, StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class StoreReservationControllerTest {

    private static final String BASE_URL =
            "/api/v1/store-operator/stores/22/reservations";
    private static final String DETAIL_URL = BASE_URL + "/77";
    private static final String CANCELLATION_URL = DETAIL_URL + "/cancellations";
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private ReservationCancellationCommandFacade reservationCancellationCommandFacade;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("인증 토큰이 없으면 운영자 예약 목록을 조회할 수 없다")
    void rejectsMissingBearerToken() throws Exception {
        // when & then
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("일반 사용자 토큰으로 운영자 예약 목록을 조회할 수 없다")
    void rejectsConsumerToken() throws Exception {
        // given
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 33L));

        // when & then
        mockMvc.perform(get(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("운영자 예약 목록은 필터·페이지와 공통 응답 봉투를 반환한다")
    void returnsStoreReservationPageEnvelope() throws Exception {
        // given
        authenticateStoreOperator(33L);
        given(reservationService.getStoreReservations(
                eq(33L),
                eq(22L),
                any(StoreReservationSearchRequest.class)
        )).willReturn(storeReservationPage());

        // when & then
        mockMvc.perform(get(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .param("serviceDate", "2026-08-01")
                        .param("status", "CONFIRMED")
                        .param("page", "1")
                        .param("size", "10")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].reservationId").value("77"))
                .andExpect(jsonPath("$.data.items[0].totalPartySize").value(3))
                .andExpect(jsonPath("$.data.page.number").value(1))
                .andExpect(jsonPath("$.data.page.size").value(10));

        ArgumentCaptor<StoreReservationSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(StoreReservationSearchRequest.class);
        then(reservationService).should()
                .getStoreReservations(eq(33L), eq(22L), requestCaptor.capture());
        StoreReservationSearchRequest request = requestCaptor.getValue();
        assertThat(request.serviceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(request.status()).isEqualTo(StoreReservationSearchRequest.Status.CONFIRMED);
        assertThat(request.page()).isEqualTo(1);
        assertThat(request.size()).isEqualTo(10);
        assertThat(request.order()).isEqualTo(StoreReservationSearchRequest.Order.CREATED_AT_DESC);
    }

    @Test
    @DisplayName("운영자 예약 목록은 Idempotency-Key 없이 조회한다")
    void doesNotRequireIdempotencyKeyForRead() throws Exception {
        // given
        authenticateStoreOperator(33L);
        given(reservationService.getStoreReservations(
                eq(33L),
                eq(22L),
                any(StoreReservationSearchRequest.class)
        )).willReturn(emptyStoreReservationPage());

        // when & then
        mockMvc.perform(get(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    @DisplayName("0 이하 매장 ID는 Service 호출 전에 거절한다")
    void rejectsNonPositiveStoreId() throws Exception {
        // given
        authenticateStoreOperator(33L);

        // when & then
        mockMvc.perform(get("/api/v1/store-operator/stores/0/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.details[0].field").value("storeId"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("허용하지 않은 상태·정렬은 400으로 거절한다")
    void rejectsUnapprovedStatusAndSort() throws Exception {
        // given
        authenticateStoreOperator(33L);

        // when & then
        mockMvc.perform(get(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .param("status", "REQUESTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        mockMvc.perform(get(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .param("sort", "status,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("매장 관리 권한이 없으면 STORE_003을 반환한다")
    void returnsStoreAccessDenied() throws Exception {
        // given
        authenticateStoreOperator(33L);
        given(reservationService.getStoreReservations(
                eq(33L),
                eq(22L),
                any(StoreReservationSearchRequest.class)
        )).willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED));

        // when & then
        mockMvc.perform(get(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    @Test
    @DisplayName("운영자 예약 상세를 공통 성공 봉투와 거래 스냅샷으로 반환한다")
    void returnsStoreReservationDetailEnvelope() throws Exception {
        // given
        authenticateStoreOperator(33L);
        given(reservationService.getStoreReservation(33L, 22L, 77L))
                .willReturn(detailResponse());

        // when & then
        mockMvc.perform(get(DETAIL_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
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
                .andExpect(jsonPath("$.data.createdAt")
                        .value("2026-08-01T09:00:00Z"))
                .andExpect(jsonPath("$.data.endTime").doesNotExist())
                .andExpect(jsonPath("$.data.occupancyEndAt").doesNotExist());

        then(reservationService).should().getStoreReservation(33L, 22L, 77L);
    }

    @Test
    @DisplayName("운영자 예약 상세 GET은 Idempotency-Key 없이 조회한다")
    void doesNotRequireIdempotencyKeyForDetailRead() throws Exception {
        // given
        authenticateStoreOperator(33L);
        given(reservationService.getStoreReservation(33L, 22L, 77L))
                .willReturn(detailResponse());

        // when & then
        mockMvc.perform(get(DETAIL_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId").value("77"));
    }

    @Test
    @DisplayName("매장 관리 권한이 없으면 운영자 예약 상세를 노출하지 않는다")
    void returnsStoreAccessDeniedBeforeDetailDisclosure() throws Exception {
        // given
        authenticateStoreOperator(33L);
        given(reservationService.getStoreReservation(33L, 22L, 77L))
                .willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED));

        // when & then
        mockMvc.perform(get(DETAIL_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    @Test
    @DisplayName("대상 매장 범위에서 찾을 수 없는 예약은 숨김 404를 반환한다")
    void returnsHiddenStoreReservationNotFound() throws Exception {
        // given
        authenticateStoreOperator(33L);
        given(reservationService.getStoreReservation(33L, 22L, 77L))
                .willThrow(new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(get(DETAIL_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_001"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("0 이하 운영자 상세 예약 ID도 검증 오류 대신 같은 숨김 404를 반환한다")
    void returnsHiddenNotFoundForNonPositiveDetailReservationId(long reservationId)
            throws Exception {
        // given
        authenticateStoreOperator(33L);
        given(reservationService.getStoreReservation(33L, 22L, reservationId))
                .willThrow(new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(get(BASE_URL + "/{reservationId}", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_001"));

        then(reservationService).should()
                .getStoreReservation(33L, 22L, reservationId);
    }

    @Test
    void cancelsStoreReservationWithAuthenticatedPrincipalAndIdempotencyKey() throws Exception {
        authenticateStoreOperator(33L);
        given(reservationCancellationCommandFacade.cancelByStoreOperator(
                eq(33L), eq(22L), eq(77L), any(IdempotencyKey.class), any(StoreCancellationRequest.class)))
                .willReturn(new ReservationCancellationCommandResult(200, cancelledDetailResponse()));

        mockMvc.perform(post(CANCELLATION_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"store closure\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledBy").value("STORE_OPERATOR"))
                .andExpect(jsonPath("$.data.cancelledAt").doesNotExist());

        ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
        ArgumentCaptor<StoreCancellationRequest> requestCaptor =
                ArgumentCaptor.forClass(StoreCancellationRequest.class);
        then(reservationCancellationCommandFacade).should().cancelByStoreOperator(
                eq(33L), eq(22L), eq(77L), keyCaptor.capture(), requestCaptor.capture());
        assertThat(keyCaptor.getValue().value()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(requestCaptor.getValue().reason()).isEqualTo("store closure");
    }

    @Test
    void rejectsStoreCancellationKeyFailuresBeforeFacadeInvocation() throws Exception {
        authenticateStoreOperator(33L);

        mockMvc.perform(post(CANCELLATION_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"store closure\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
        mockMvc.perform(post(CANCELLATION_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"store closure\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_004"));

        then(reservationCancellationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    void rejectsInvalidStoreCancellationBodyBeforeFacadeInvocation() throws Exception {
        authenticateStoreOperator(33L);

        for (String body : List.of("{}", "{\"reason\":\"\"}",
                "{\"reason\":\"" + "a".repeat(501) + "\"}")) {
            mockMvc.perform(post(CANCELLATION_URL)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"));
        }
        mockMvc.perform(post(CANCELLATION_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(reservationCancellationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    void acceptsWhitespaceOnlyStoreCancellationReason() throws Exception {
        authenticateStoreOperator(33L);
        given(reservationCancellationCommandFacade.cancelByStoreOperator(
                eq(33L), eq(22L), eq(77L), any(IdempotencyKey.class), any(StoreCancellationRequest.class)))
                .willReturn(new ReservationCancellationCommandResult(200, cancelledDetailResponse()));

        mockMvc.perform(post(CANCELLATION_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsUnauthenticatedAndConsumerStoreCancellation() throws Exception {
        mockMvc.perform(post(CANCELLATION_URL)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"store closure\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));
        mockMvc.perform(post(CANCELLATION_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"store closure\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(reservationCancellationCommandFacade).shouldHaveNoInteractions();
    }

    @Test
    void passesThroughStoreCancellationServiceExceptions() throws Exception {
        authenticateStoreOperator(33L);
        for (com.miriyum.global.exception.ErrorCode errorCode : List.of(
                StoreErrorCode.ACCESS_DENIED,
                ReservationErrorCode.RESERVATION_NOT_FOUND,
                ReservationErrorCode.INVALID_STATE_TRANSITION,
                ReservationErrorCode.CANCELLATION_NOT_ALLOWED)) {
            given(reservationCancellationCommandFacade.cancelByStoreOperator(
                    eq(33L), eq(22L), eq(77L), any(IdempotencyKey.class), any(StoreCancellationRequest.class)))
                    .willThrow(new ServiceException(errorCode));

            mockMvc.perform(post(CANCELLATION_URL)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"store closure\"}"))
                    .andExpect(status().is(errorCode == StoreErrorCode.ACCESS_DENIED ? 403
                            : errorCode == ReservationErrorCode.RESERVATION_NOT_FOUND ? 404 : 409))
                    .andExpect(jsonPath("$.code").value(errorCode.getCode()));
        }
    }

    @Test
    void deniesUnknownPostAndNonApprovedMethodInStoreReservationFamily() throws Exception {
        authenticateStoreOperator(33L);

        mockMvc.perform(post(DETAIL_URL + "/unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));
        mockMvc.perform(get(CANCELLATION_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));

        then(reservationCancellationCommandFacade).shouldHaveNoInteractions();
    }

    private void authenticateStoreOperator(long accountId) {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, accountId));
    }

    private StoreReservationPageResponse storeReservationPage() {
        return new StoreReservationPageResponse(
                List.of(new StoreReservationSummaryResponse(
                        "77",
                        LocalDate.of(2026, 8, 1),
                        CustomerReservationTimeStatus.RESOLVED,
                        OffsetDateTime.parse("2026-08-01T18:00:00+09:00"),
                        OffsetDateTime.parse("2026-08-01T19:00:00+09:00"),
                        "Asia/Seoul",
                        3,
                        "CONFIRMED"
                )),
                new PageMetadata(1, 10, 11, 2, false)
        );
    }

    private StoreReservationPageResponse emptyStoreReservationPage() {
        return new StoreReservationPageResponse(
                List.of(),
                new PageMetadata(0, 20, 0, 0, false)
        );
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

    private ReservationDetailResponse cancelledDetailResponse() {
        ReservationDetailResponse detail = detailResponse();
        return new ReservationDetailResponse(
                detail.reservationId(), detail.storeId(), detail.storeName(), detail.serviceDate(),
                detail.timeStatus(), detail.startAt(), detail.serviceEndAt(), detail.timeZoneId(),
                detail.party(), "CANCELLED", detail.menuSelections(), detail.createdAt(), "STORE_OPERATOR",
                "store closure");
    }
}
