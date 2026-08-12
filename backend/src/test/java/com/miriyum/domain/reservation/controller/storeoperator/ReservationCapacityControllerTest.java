package com.miriyum.domain.reservation.controller.storeoperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.reservation.dto.response.ReservationCapacitiesResponse;
import com.miriyum.domain.reservation.dto.response.ReservationCapacityBucketResponse;
import com.miriyum.domain.reservation.service.ReservationCapacityCommandFacade;
import com.miriyum.domain.reservation.service.ReservationCapacityCommandResult;
import com.miriyum.domain.store.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReservationCapacityController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class ReservationCapacityControllerTest {

    private static final String URL =
            "/api/v1/store-operators/stores/7/reservation-capacities/2026-08-10";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationCapacityCommandFacade commandFacade;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void requiresAStoreOperatorBearerToken() throws Exception {
        mockMvc.perform(put(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void requiresAnIdempotencyKey() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void validatesEveryCapacityBucketBeforeCallingTheCommand() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"maxPeople\": 8", "\"maxPeople\": 0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void rejectsSecondPrecisionCapacityTimes() throws Exception {
        // given
        authenticateStoreOperator();
        given(commandFacade.replace(
                eq(11L),
                eq(7L),
                eq(LocalDate.of(2026, 8, 10)),
                any(IdempotencyKey.class),
                any()
        )).willReturn(successfulResult());

        // when & then
        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace(
                                "\"startTime\": \"18:00\"",
                                "\"startTime\": \"18:00:30\""
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void rejectsHHmmssCapacityTimesEvenWhenSecondsAreZero() throws Exception {
        // given
        authenticateStoreOperator();
        given(commandFacade.replace(
                eq(11L),
                eq(7L),
                eq(LocalDate.of(2026, 8, 10)),
                any(IdempotencyKey.class),
                any()
        )).willReturn(successfulResult());

        // when & then
        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace(
                                "\"startTime\": \"18:00\"",
                                "\"startTime\": \"18:00:00\""
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void rejectsHHmmssCapacityEndTimesEvenWhenSecondsAreZero() throws Exception {
        // given
        authenticateStoreOperator();
        given(commandFacade.replace(
                eq(11L),
                eq(7L),
                eq(LocalDate.of(2026, 8, 10)),
                any(IdempotencyKey.class),
                any()
        )).willReturn(successfulResult());

        // when & then
        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace(
                                "\"endTime\": \"18:30\"",
                                "\"endTime\": \"18:30:00\""
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void returnsStoreNotFoundWhenTheManagementTargetDoesNotExist() throws Exception {
        // given
        authenticateStoreOperator();
        given(commandFacade.replace(
                eq(11L),
                eq(7L),
                eq(LocalDate.of(2026, 8, 10)),
                any(IdempotencyKey.class),
                any()
        )).willThrow(new ServiceException(StoreErrorCode.STORE_NOT_FOUND));

        // when & then
        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_001"));
    }

    @Test
    void returnsTheCanonicalCapacityPublicationResponse() throws Exception {
        authenticateStoreOperator();
        LocalDate serviceDate = LocalDate.of(2026, 8, 10);
        given(commandFacade.replace(
                eq(11L),
                eq(7L),
                eq(serviceDate),
                any(IdempotencyKey.class),
                any()
        )).willReturn(successfulResult());

        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.serviceDate").value("2026-08-10"))
                .andExpect(jsonPath("$.data.policyVersion").value(3))
                .andExpect(jsonPath("$.data.buckets[0].capacityBucketId").value("101"))
                .andExpect(jsonPath("$.data.buckets[0].startTime").value("18:00"))
                .andExpect(jsonPath("$.data.buckets[0].availablePeople").value(3));
    }

    private static ReservationCapacityCommandResult successfulResult() {
        LocalDate serviceDate = LocalDate.of(2026, 8, 10);
        return new ReservationCapacityCommandResult(
                200,
                new ReservationCapacitiesResponse(
                        serviceDate,
                        3L,
                        List.of(new ReservationCapacityBucketResponse(
                                "101",
                                LocalTime.of(18, 0),
                                LocalTime.of(18, 30),
                                8,
                                2,
                                5,
                                1,
                                3,
                                1
                        ))
                )
        );
    }

    private void authenticateStoreOperator() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 11L));
    }

    private static String validRequest() {
        return """
                {
                  "buckets": [
                    {
                      "startTime": "18:00",
                      "endTime": "18:30",
                      "maxPeople": 8,
                      "maxTeams": 2,
                      "minPartySize": 1,
                      "maxPartySize": 4,
                      "infantsAllowed": true
                    }
                  ]
                }
                """;
    }
}
