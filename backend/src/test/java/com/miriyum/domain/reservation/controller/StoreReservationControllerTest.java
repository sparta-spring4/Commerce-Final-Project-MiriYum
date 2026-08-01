package com.miriyum.domain.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.reservation.dto.request.StoreReservationSearchRequest;
import com.miriyum.domain.reservation.dto.response.StoreReservationPageResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationSummaryResponse;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.core.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.PageMetadata;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreReservationController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class StoreReservationControllerTest {

    private static final String BASE_URL =
            "/api/v1/store-operator/stores/22/reservations";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

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

    private void authenticateStoreOperator(long accountId) {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, accountId));
    }

    private StoreReservationPageResponse storeReservationPage() {
        return new StoreReservationPageResponse(
                List.of(new StoreReservationSummaryResponse(
                        "77",
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
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
}
