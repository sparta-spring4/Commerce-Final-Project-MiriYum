package com.miriyum.domain.reservation.controller.storeoperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.reservation.config.ReservationSecurityConfig;
import com.miriyum.domain.reservation.dto.request.ReservationCheckInRequest;
import com.miriyum.domain.reservation.dto.request.ReservationNoShowRequest;
import com.miriyum.domain.reservation.service.ReservationVisitCommandFacade;
import com.miriyum.domain.reservation.service.ReservationVisitCommandResult;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotencyKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreReservationCheckInController.class)
@Import({ReservationSecurityConfig.class, GlobalExceptionHandler.class})
class StoreReservationCheckInControllerTest {

    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String TOKEN = "rqg_v1_" + "A".repeat(43);
    private static final String SCAN_URL =
            "/api/v1/store-operators/stores/22/reservation-check-ins";
    private static final String NO_SHOW_URL =
            "/api/v1/store-operators/stores/22/reservations/77/no-shows";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ReservationVisitCommandFacade visitFacade;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    @Test
    void scansQrWithStoreOperatorAndRequiredIdempotencyKey() throws Exception {
        authenticateStoreOperator();
        given(visitFacade.checkIn(eq(33L), eq(22L), any(IdempotencyKey.class),
                any(ReservationCheckInRequest.class)))
                .willReturn(new ReservationVisitCommandResult(200, null));

        mockMvc.perform(post(SCAN_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        then(visitFacade).should().checkIn(
                eq(33L), eq(22L), any(IdempotencyKey.class),
                eq(new ReservationCheckInRequest(TOKEN))
        );
    }

    @Test
    void confirmsNoShowWithExplicitReason() throws Exception {
        authenticateStoreOperator();
        given(visitFacade.markNoShow(eq(33L), eq(22L), eq(77L),
                any(IdempotencyKey.class), any(ReservationNoShowRequest.class)))
                .willReturn(new ReservationVisitCommandResult(200, null));

        mockMvc.perform(post(NO_SHOW_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"UNCLEAR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        then(visitFacade).should().markNoShow(
                eq(33L), eq(22L), eq(77L), any(IdempotencyKey.class),
                eq(new ReservationNoShowRequest(
                        com.miriyum.domain.reservation.entity.ReservationNoShowReason.UNCLEAR
                ))
        );
    }

    @Test
    void rejectsMissingIdempotencyKeyBeforeFacade() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(post(SCAN_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        then(visitFacade).shouldHaveNoInteractions();
    }

    @Test
    void rejectsMalformedQrBeforeFacade() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(post(SCAN_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrToken\":\"raw-secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(visitFacade).shouldHaveNoInteractions();
    }

    @Test
    void rejectsConsumerNamespace() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 33L));

        mockMvc.perform(post(SCAN_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isUnauthorized());

        then(visitFacade).shouldHaveNoInteractions();
    }

    private void authenticateStoreOperator() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 33L));
    }
}
