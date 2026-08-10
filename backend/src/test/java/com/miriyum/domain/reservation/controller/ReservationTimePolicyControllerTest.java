package com.miriyum.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.reservation.dto.response.ReservationTimePolicyResponse;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationTimePolicyCommandFacade;
import com.miriyum.domain.reservation.service.ReservationTimePolicyCommandResult;
import com.miriyum.domain.store.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(ReservationTimePolicyController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class ReservationTimePolicyControllerTest {

    private static final String BASE_URL =
            "/api/v1/store-operator/stores/7/reservation-time-policies";
    private static final String TEST_KEY =
            "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationTimePolicyCommandFacade commandFacade;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @ParameterizedTest
    @ValueSource(strings = {"draft", "publication", "cancellation"})
    void everyCommandRequiresBearerToken(String command) throws Exception {
        mockMvc.perform(commandRequest(command)
                        .header("Idempotency-Key", TEST_KEY)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "publication", "cancellation"})
    void everyCommandRejectsConsumerBearerNamespace(String command) throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));

        mockMvc.perform(commandRequest(command)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Idempotency-Key", TEST_KEY)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "publication", "cancellation"})
    void everyCommandRequiresIdempotencyKey(String command) throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(commandRequest(command)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void invalidDurationsReturnCommon001() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(put(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slotInterval": 30,
                                  "serviceDuration": 1440,
                                  "turnoverDuration": 1
                                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void unknownRequestFieldReturnsCommon002() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(put(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slotInterval": 30,
                                  "serviceDuration": 90,
                                  "turnoverDuration": 15,
                                  "endTime": "20:00:00"
                                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void scheduledPublicationRequiresEffectiveAt() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(post(BASE_URL + "/1/publication")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicationMode": "SCHEDULED",
                                  "changeReason": "저녁 운영 확대"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void immediatePublicationRejectsExplicitNullEffectiveAt() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(post(BASE_URL + "/1/publication")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicationMode": "IMMEDIATE",
                                  "effectiveAt": null,
                                  "changeReason": "즉시 적용"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void publicationRejectsUnknownRequestField() throws Exception {
        authenticateStoreOperator();

        mockMvc.perform(post(BASE_URL + "/1/publication")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicationMode": "IMMEDIATE",
                                  "changeReason": "즉시 적용",
                                  "endTime": "20:00:00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(commandFacade).shouldHaveNoInteractions();
    }

    @Test
    void draftPublicationAndCancellationReturnCanonicalResponse() throws Exception {
        authenticateStoreOperator();
        given(commandFacade.createDraft(
                eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willReturn(new ReservationTimePolicyCommandResult<>(
                        200,
                        response(ReservationTimePolicyStatus.DRAFT, null)
                ));
        given(commandFacade.publish(
                eq(11L), eq(7L), eq(1L), any(IdempotencyKey.class), any()))
                .willReturn(new ReservationTimePolicyCommandResult<>(
                        200,
                        response(
                                ReservationTimePolicyStatus.SCHEDULED,
                                OffsetDateTime.parse("2026-08-05T12:00:00+09:00")
                        )
                ));
        given(commandFacade.cancelPublication(
                eq(11L), eq(7L), eq(1L), any(IdempotencyKey.class), any()))
                .willReturn(new ReservationTimePolicyCommandResult<>(
                        200,
                        response(ReservationTimePolicyStatus.DRAFT, null)
                ));

        mockMvc.perform(put(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").value("7"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(post(BASE_URL + "/1/publication")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicationMode": "SCHEDULED",
                                  "effectiveAt": "2026-08-05T12:00:00+09:00",
                                  "changeReason": "저녁 운영 확대"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.effectiveAt")
                        .value("2026-08-05T12:00:00+09:00"));

        mockMvc.perform(post(BASE_URL + "/1/publication-cancellation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeReason": "적용 보류"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void reservationLifecycleConflictKeepsReservation010() throws Exception {
        authenticateStoreOperator();
        given(commandFacade.createDraft(
                eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(
                        ReservationErrorCode.TIME_POLICY_CONFLICT));

        mockMvc.perform(put(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_010"));
    }

    @Test
    void storeAuthorityErrorIsNotTranslatedToReservationError() throws Exception {
        authenticateStoreOperator();
        given(commandFacade.createDraft(
                eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT));

        mockMvc.perform(put(BASE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_005"));
    }

    private void authenticateStoreOperator() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 11L));
    }

    private String validDraftJson() {
        return """
                {
                  "slotInterval": 30,
                  "serviceDuration": 90,
                  "turnoverDuration": 15
                }
                """;
    }

    private MockHttpServletRequestBuilder commandRequest(String command) {
        return switch (command) {
            case "draft" -> put(BASE_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validDraftJson());
            case "publication" -> post(BASE_URL + "/1/publication")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "publicationMode": "IMMEDIATE",
                              "changeReason": "즉시 적용"
                            }
                            """);
            case "cancellation" -> post(BASE_URL + "/1/publication-cancellation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"changeReason": "적용 보류"}
                            """);
            default -> throw new IllegalArgumentException("unknown command: " + command);
        };
    }

    private ReservationTimePolicyResponse response(
            ReservationTimePolicyStatus status,
            OffsetDateTime effectiveAt
    ) {
        return new ReservationTimePolicyResponse(
                "7",
                1L,
                30,
                90,
                15,
                status,
                effectiveAt
        );
    }
}
