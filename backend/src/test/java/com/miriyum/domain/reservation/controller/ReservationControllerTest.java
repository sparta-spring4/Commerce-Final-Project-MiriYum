package com.miriyum.domain.reservation.controller;

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
import com.miriyum.domain.reservation.dto.response.CustomerReservationTimeStatus;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationMenuSelectionResponse;
import com.miriyum.domain.reservation.dto.response.ReservationPartyResponse;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReservationController.class)
@Import({ReservationSecurityConfig.class, GlobalExceptionHandler.class})
class ReservationControllerTest {

    private static final String DETAIL_URL = "/api/v1/reservations/77";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

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
        mockMvc.perform(get("/api/v1/reservations/{reservationId}", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_001"));

        then(reservationService).should()
                .getConsumerReservation(11L, reservationId);
    }

    @Test
    @DisplayName("소비자 예약 루트 POST는 예약 상세 체인에서 거부한다")
    void deniesPostReservationRoot() throws Exception {
        // given
        authenticateConsumer(11L);

        // when & then
        mockMvc.perform(post("/api/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("아직 구현하지 않은 소비자 취소 경로는 예약 상세 체인에서 거부한다")
    void deniesUnimplementedCancellationPath() throws Exception {
        // given
        authenticateConsumer(11L);

        // when & then
        mockMvc.perform(post("/api/v1/reservations/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));

        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("승인하지 않은 예약 하위 경로는 예약 상세 체인에서 거부한다")
    void deniesUnknownReservationSubpath() throws Exception {
        // given
        authenticateConsumer(11L);

        // when & then
        mockMvc.perform(get("/api/v1/reservations/77/unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_006"));

        then(reservationService).shouldHaveNoInteractions();
    }

    private void authenticateConsumer(long accountId) {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, accountId));
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
}
