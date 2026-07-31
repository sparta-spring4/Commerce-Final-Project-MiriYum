package com.miriyum.domain.store.schedule.controller;

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
import com.miriyum.domain.store.core.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.DailyOperatingScheduleRequest;
import com.miriyum.domain.store.schedule.dto.DailyReservationSlotsRequest;
import com.miriyum.domain.store.schedule.dto.OperatingHoursResponse;
import com.miriyum.domain.store.schedule.dto.ReservationTimeSlotsResponse;
import com.miriyum.domain.store.schedule.dto.TimeRangeRequest;
import com.miriyum.domain.store.schedule.service.ScheduleCommandResult;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
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

@WebMvcTest(StoreScheduleController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class StoreScheduleControllerTest {

    private static final String BASE_URL =
            "/api/v1/store-operator/stores/7";
    private static final String TEST_KEY =
            "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreScheduleService storeScheduleService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void missingBearerTokenReturnsAuth001() throws Exception {
        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOperatingJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test
    void consumerTokenReturnsAuth004() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));

        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer consumer-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOperatingJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
    }

    @Test
    void missingIdempotencyKeyReturnsCommon003() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOperatingJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    void invalidIdempotencyKeyReturnsCommon004() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOperatingJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_004"));
    }

    @Test
    void invalidWeekReturnsCommon001() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "days": [{
                                    "dayOfWeek": "MONDAY",
                                    "businessHours": [],
                                    "breakTimes": []
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void nullOperatingDayReturnsCommon001() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(operatingJsonWithNullDay()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(storeScheduleService).shouldHaveNoInteractions();
    }

    @Test
    void nullOperatingRangeReturnsCommon001() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOperatingJson().replace(
                                "\"businessHours\": [{",
                                "\"businessHours\": [null, {")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(storeScheduleService).shouldHaveNoInteractions();
    }

    @Test
    void nullReservationDayReturnsCommon001() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(BASE_URL + "/reservation-time-slots")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJsonWithNullDay()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(storeScheduleService).shouldHaveNoInteractions();
    }

    @Test
    void nullReservationRangeReturnsCommon001() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(BASE_URL + "/reservation-time-slots")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson().replace(
                                "\"slots\": [{",
                                "\"slots\": [null, {")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(storeScheduleService).shouldHaveNoInteractions();
    }

    @Test
    void secondPrecisionInputReturnsCommon002() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(BASE_URL + "/reservation-time-slots")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson().replace(
                                "\"19:00\"",
                                "\"19:00:00\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));

        then(storeScheduleService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveStoreIdRejectsOperatingPublicationBeforeService(
            long storeId
    ) throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(
                        "/api/v1/store-operator/stores/{storeId}/operating-hours",
                        storeId)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOperatingJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.details[0].field").value("storeId"));

        then(storeScheduleService).shouldHaveNoInteractions();
    }

    @Test
    void operatingHoursReturnsPublishedVersion() throws Exception {
        authenticateStoreOperator(11L);
        given(storeScheduleService.replaceOperatingHours(
                eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willReturn(new ScheduleCommandResult<>(
                        200, operatingResponse()));

        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOperatingJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.days[0].dayOfWeek")
                        .value("MONDAY"))
                .andExpect(jsonPath(
                        "$.data.days[0].businessHours[0].startTime")
                        .value("18:00"))
                .andExpect(jsonPath(
                        "$.data.days[0].businessHours[0].endTime")
                        .value("02:00"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveStoreIdRejectsReservationPublicationBeforeService(
            long storeId
    ) throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(put(
                        "/api/v1/store-operator/stores/{storeId}/reservation-time-slots",
                        storeId)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.details[0].field").value("storeId"));

        then(storeScheduleService).shouldHaveNoInteractions();
    }

    @Test
    void reservationTimeSlotsReturnsReferencedOperatingVersion()
            throws Exception {
        authenticateStoreOperator(11L);
        given(storeScheduleService.replaceReservationTimeSlots(
                eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willReturn(new ScheduleCommandResult<>(
                        200, reservationResponse()));

        mockMvc.perform(put(BASE_URL + "/reservation-time-slots")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath(
                        "$.data.days[0].slots[0].startTime")
                        .value("19:00"));
    }

    @Test
    void reservationConflictReturnsStore006() throws Exception {
        authenticateStoreOperator(11L);
        given(storeScheduleService.replaceReservationTimeSlots(
                eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(
                        StoreErrorCode.SCHEDULE_CONFLICT));

        mockMvc.perform(put(BASE_URL + "/reservation-time-slots")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validReservationJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_006"));
    }

    @Test
    void otherOperatorReturnsStore003() throws Exception {
        authenticateStoreOperator(12L);
        given(storeScheduleService.replaceOperatingHours(
                eq(12L), eq(7L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(
                        StoreErrorCode.ACCESS_DENIED));

        mockMvc.perform(put(BASE_URL + "/operating-hours")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOperatingJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    private void authenticateStoreOperator(long accountId) {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(
                        TokenNamespace.STORE_OPERATOR, accountId));
    }

    private OperatingHoursResponse operatingResponse() {
        return new OperatingHoursResponse(1L, Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyOperatingScheduleRequest(
                        day,
                        day == DayOfWeek.MONDAY
                                ? List.of(new TimeRangeRequest(
                                        LocalTime.of(18, 0),
                                        LocalTime.of(2, 0)))
                                : List.of(),
                        List.of()))
                .toList());
    }

    private ReservationTimeSlotsResponse reservationResponse() {
        return new ReservationTimeSlotsResponse(
                1L,
                Arrays.stream(DayOfWeek.values())
                        .map(day -> new DailyReservationSlotsRequest(
                                day,
                                day == DayOfWeek.MONDAY
                                        ? List.of(new TimeRangeRequest(
                                                LocalTime.of(19, 0),
                                                LocalTime.of(20, 0)))
                                        : List.of()))
                        .toList());
    }

    private String validOperatingJson() {
        return """
                {
                  "days": [
                    {
                      "dayOfWeek": "MONDAY",
                      "businessHours": [{
                        "startTime": "18:00",
                        "endTime": "02:00"
                      }],
                      "breakTimes": []
                    },
                    {"dayOfWeek": "TUESDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "WEDNESDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "THURSDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "FRIDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "SATURDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "SUNDAY", "businessHours": [], "breakTimes": []}
                  ]
                }
                """;
    }

    private String validReservationJson() {
        return """
                {
                  "days": [
                    {"dayOfWeek": "MONDAY", "slots": [{
                      "startTime": "19:00",
                      "endTime": "20:00"
                    }]},
                    {"dayOfWeek": "TUESDAY", "slots": []},
                    {"dayOfWeek": "WEDNESDAY", "slots": []},
                    {"dayOfWeek": "THURSDAY", "slots": []},
                    {"dayOfWeek": "FRIDAY", "slots": []},
                    {"dayOfWeek": "SATURDAY", "slots": []},
                    {"dayOfWeek": "SUNDAY", "slots": []}
                  ]
                }
                """;
    }

    private String operatingJsonWithNullDay() {
        return """
                {
                  "days": [
                    null,
                    {"dayOfWeek": "TUESDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "WEDNESDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "THURSDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "FRIDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "SATURDAY", "businessHours": [], "breakTimes": []},
                    {"dayOfWeek": "SUNDAY", "businessHours": [], "breakTimes": []}
                  ]
                }
                """;
    }

    private String reservationJsonWithNullDay() {
        return """
                {
                  "days": [
                    null,
                    {"dayOfWeek": "TUESDAY", "slots": []},
                    {"dayOfWeek": "WEDNESDAY", "slots": []},
                    {"dayOfWeek": "THURSDAY", "slots": []},
                    {"dayOfWeek": "FRIDAY", "slots": []},
                    {"dayOfWeek": "SATURDAY", "slots": []},
                    {"dayOfWeek": "SUNDAY", "slots": []}
                  ]
                }
                """;
    }
}
