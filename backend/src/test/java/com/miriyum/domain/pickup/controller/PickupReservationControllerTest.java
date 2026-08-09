package com.miriyum.domain.pickup.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.pickup.config.PickupSecurityConfig;
import com.miriyum.domain.pickup.dto.response.PickupReservationItemResponse;
import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;
import com.miriyum.domain.pickup.entity.PickupStatus;
import com.miriyum.domain.pickup.service.PickupCommandResult;
import com.miriyum.domain.pickup.service.PickupReservationService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PickupReservationController.class)
@Import({PickupSecurityConfig.class, GlobalExceptionHandler.class})
class PickupReservationControllerTest {

    private static final String URL = "/api/v1/pickup-reservations";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired MockMvc mockMvc;
    @MockitoBean PickupReservationService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void createsPickupForAuthenticatedConsumer() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));
        given(service.create(eq(11L), any(IdempotencyKey.class), any()))
                .willReturn(new PickupCommandResult(201, response()));

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeId": "22",
                                  "pickupDate": "2026-08-10",
                                  "pickupTime": "12:00",
                                  "menuSelections": [{"menuId": "33", "quantity": 2}]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.pickupReservationId").value("77"))
                .andExpect(jsonPath("$.data.items[0].menuId").value("33"));
        then(service).should().create(eq(11L), any(IdempotencyKey.class), any());
    }

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":"22","pickupDate":"2026-08-10",
                                 "pickupTime":"12:00","menuSelections":[{"menuId":"33","quantity":2}]}
                                """))
                .andExpect(status().isBadRequest());
        then(service).shouldHaveNoInteractions();
    }

    @Test
    void returnsAuthenticatedConsumersPickupDetail() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));
        given(service.getConsumerPickup(11L, 77L)).willReturn(response());

        mockMvc.perform(get(URL + "/77")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickupReservationId").value("77"));
        then(service).should().getConsumerPickup(11L, 77L);
    }

    @Test
    void cancelsAuthenticatedConsumersPickup() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));
        given(service.cancelByConsumer(
                eq(11L), eq(77L), any(IdempotencyKey.class), any()))
                .willReturn(new PickupCommandResult(200, response()));

        mockMvc.perform(post(URL + "/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"일정 변경\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        then(service).should().cancelByConsumer(
                eq(11L), eq(77L), any(IdempotencyKey.class), any());
    }

    private static PickupReservationResponse response() {
        return new PickupReservationResponse(
                "77", "22", "미리윰 강남점", LocalDate.of(2026, 8, 10),
                LocalTime.NOON, PickupStatus.CONFIRMED,
                List.of(new PickupReservationItemResponse("33", "바질 파스타", 12_000, 2)),
                null, null, OffsetDateTime.parse("2026-08-09T10:00:00+09:00"));
    }
}
