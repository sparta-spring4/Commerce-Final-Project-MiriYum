package com.miriyum.domain.pickup.controller;

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
import com.miriyum.domain.pickup.dto.response.PickupReservationItemResponse;
import com.miriyum.domain.pickup.dto.response.PickupReservationPageResponse;
import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;
import com.miriyum.domain.pickup.entity.PickupStatus;
import com.miriyum.domain.pickup.service.PickupCommandResult;
import com.miriyum.domain.pickup.service.PickupStoreManagementService;
import com.miriyum.domain.store.core.config.StoreManagementSecurityConfig;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.PageMetadata;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PickupStoreManagementController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class PickupStoreManagementControllerTest {

    private static final String ROOT =
            "/api/v1/store-operator/stores/22/pickup-reservations";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired MockMvc mockMvc;
    @MockitoBean PickupStoreManagementService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void authenticate() {
        given(jwtTokenProvider.parseAccessToken("operator-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 31L));
    }

    @Test
    void listsManagedStoresPickups() throws Exception {
        given(service.list(eq(31L), eq(22L), any()))
                .willReturn(new PickupReservationPageResponse(
                        List.of(response()), new PageMetadata(0, 20, 1, 1, false)));

        mockMvc.perform(get(ROOT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator-token")
                        .queryParam("pickupDate", "2026-08-10")
                        .queryParam("status", "CONFIRMED")
                        .queryParam("sort", "pickupDate,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].pickupReservationId").value("77"))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
        then(service).should().list(eq(31L), eq(22L), any());
    }

    @Test
    void getsManagedStoresPickupDetail() throws Exception {
        given(service.getDetail(31L, 22L, 77L)).willReturn(response());

        mockMvc.perform(get(ROOT + "/77")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickupReservationId").value("77"));
    }

    @Test
    void cancelsManagedStoresPickupWithRequiredReason() throws Exception {
        given(service.cancel(eq(31L), eq(22L), eq(77L),
                any(IdempotencyKey.class), any()))
                .willReturn(new PickupCommandResult(200, response()));

        mockMvc.perform(post(ROOT + "/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"재료 소진\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void fulfillsManagedStoresPickup() throws Exception {
        given(service.fulfill(eq(31L), eq(22L), eq(77L), any(IdempotencyKey.class)))
                .willReturn(new PickupCommandResult(200, response()));

        mockMvc.perform(post(ROOT + "/77/fulfillments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void rejectsBlankStoreCancellationReasonAndNonEmptyFulfillmentBody() throws Exception {
        mockMvc.perform(post(ROOT + "/77/cancellations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(ROOT + "/77/fulfillments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PICKED_UP\"}"))
                .andExpect(status().isBadRequest());
        then(service).shouldHaveNoInteractions();
    }

    private static PickupReservationResponse response() {
        return new PickupReservationResponse(
                "77", "22", "미리윰 강남점", LocalDate.of(2026, 8, 10),
                LocalTime.NOON, PickupStatus.CONFIRMED,
                List.of(new PickupReservationItemResponse("33", "바질 파스타", 12_000, 2)),
                null, null, OffsetDateTime.parse("2026-08-09T10:00:00+09:00"));
    }
}
