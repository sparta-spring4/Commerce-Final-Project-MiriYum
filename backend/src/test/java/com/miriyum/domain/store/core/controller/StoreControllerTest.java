package com.miriyum.domain.store.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.store.core.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.core.dto.ManagedStoreResponse;
import com.miriyum.domain.store.core.dto.StoreGeocodingResponse;
import com.miriyum.domain.store.core.dto.StoreModesRequest;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.GeocodingStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.core.service.StoreCommandResult;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class StoreControllerTest {

    private static final String TEST_KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void missingBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/store-operator/stores/7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test
    void consumerTokenReturnsNamespaceMismatch() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));

        mockMvc.perform(get("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
    }

    @Test
    void createReturnsCreatedEnvelope() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.create(eq(11L), any(IdempotencyKey.class), any()))
                .willReturn(new StoreCommandResult(201, managedStore(7L)));

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").isString())
                .andExpect(jsonPath("$.data.storeId").value("7"))
                .andExpect(jsonPath("$.data.operationStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.geocoding.status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.geocoding.latitude").value(37.566826))
                .andExpect(jsonPath("$.data.geocoding.longitude").value(126.9786567))
                .andExpect(jsonPath("$.data.geocoding.addressVersion").value(1))
                .andExpect(jsonPath("$.data.geocoding.provider").doesNotExist());
    }

    @Test
    void createReturnsBadRequestWhenGeocodingResultDoesNotMatch() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.create(eq(11L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(CommonErrorCode.VALIDATION_FAILED));

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void createRejectsUnknownIanaTimeZone() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson().replace(
                                "Asia/Seoul",
                                "Mars/Olympus")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(storeService).shouldHaveNoInteractions();
    }

    @Test
    void createRejectsMissingOnboardingDeclarations() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJsonWithModesAndDeclarations(
                                """
                                        {
                                          "reservationEnabled": true,
                                          "menuHoldEnabled": true,
                                          "pickupEnabled": true
                                        }
                                        """,
                                "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(storeService).shouldHaveNoInteractions();
    }

    @Test
    void createRejectsFalseRequiredTermsAgreement() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJsonWithModesAndDeclarations(
                                """
                                        {
                                          "reservationEnabled": true,
                                          "menuHoldEnabled": true,
                                          "pickupEnabled": true
                                        }
                                        """,
                                """
                                        ,
                                          "applicantSelfAttested": true,
                                          "requiredTermsAgreed": false
                                        """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(storeService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveStoreIdRejectsGetBeforeService(long storeId)
            throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(get("/api/v1/store-operator/stores/{storeId}", storeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.details[0].field").value("storeId"));

        then(storeService).shouldHaveNoInteractions();
    }

    @Test
    void getReturnsManagedStoreEnvelope() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.getManagedStore(11L, 7L)).willReturn(managedStore(7L));

        mockMvc.perform(get("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").isString())
                .andExpect(jsonPath("$.data.storeId").value("7"))
                .andExpect(jsonPath("$.data.geocoding.status").value("VERIFIED"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveStoreIdRejectsPatchBeforeService(long storeId)
            throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(patch("/api/v1/store-operator/stores/{storeId}", storeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "새 이름"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.details[0].field").value("storeId"));

        then(storeService).shouldHaveNoInteractions();
    }

    @Test
    void patchReturnsUpdatedEnvelope() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.update(eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willReturn(new StoreCommandResult(200, managedStore(7L)));

        mockMvc.perform(patch("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "새 이름"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").isString())
                .andExpect(jsonPath("$.data.storeId").value("7"))
                .andExpect(jsonPath("$.data.geocoding.status").value("VERIFIED"));
    }

    @Test
    void patchReturnsServiceUnavailableWhenGeocodingProviderFails() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.update(eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE));

        mockMvc.perform(patch("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "address": "서울 중구 세종대로 110"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_012"));
    }

    @Test
    void closedStatusInGeneralPatchReturnsCommon001WithoutServiceCall()
            throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(patch("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operationStatus": "CLOSED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        then(storeService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @MethodSource("modeObjectsMissingOneRequiredField")
    void createRejectsMissingModeField(String modesJson) throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJsonWithModes(modesJson)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @ParameterizedTest
    @MethodSource("modeObjectsMissingOneRequiredField")
    void patchRejectsMissingModeField(String modesJson) throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(patch("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modes": %s
                                }
                                """.formatted(modesJson)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void invalidIdempotencyKeyReturnsCommon004() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_004"));
    }

    @Test
    void otherOperatorReturnsStore003() throws Exception {
        authenticateStoreOperator(12L);
        given(storeService.getManagedStore(12L, 7L))
                .willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED));

        mockMvc.perform(get("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    @Test
    void missingStoreReturnsStore001() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.getManagedStore(11L, 7L))
                .willThrow(new ServiceException(StoreErrorCode.STORE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_001"));
    }

    private void authenticateStoreOperator(long accountId) {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, accountId));
    }

    private String validCreateJson() {
        return createJsonWithModes("""
                {
                  "reservationEnabled": true,
                  "menuHoldEnabled": true,
                  "pickupEnabled": true
                }
                """);
    }

    private String createJsonWithModes(String modesJson) {
        return createJsonWithModesAndDeclarations(
                modesJson,
                """
                        ,
                          "applicantSelfAttested": true,
                          "requiredTermsAgreed": true
                        """);
    }

    private String createJsonWithModesAndDeclarations(
            String modesJson,
            String declarationsJson
    ) {
        return """
                {
                  "businessRegistrationNumber": "1234567890",
                  "businessType": "CAFE",
                  "name": "미리윰",
                  "description": "",
                  "region": "SEOUL",
                  "address": "서울시 중구",
                  "timeZoneId": "Asia/Seoul",
                  "storeCategoryCode": "CAFE_BAKERY",
                  "tagCodes": ["DATE"],
                  "modes": %s%s
                }
                """.formatted(modesJson, declarationsJson);
    }

    private static Stream<String> modeObjectsMissingOneRequiredField() {
        return Stream.of(
                """
                        {
                          "menuHoldEnabled": true,
                          "pickupEnabled": true
                        }
                        """,
                """
                        {
                          "reservationEnabled": true,
                          "pickupEnabled": true
                        }
                        """,
                """
                        {
                          "reservationEnabled": true,
                          "menuHoldEnabled": true
                        }
                        """);
    }

    private ManagedStoreResponse managedStore(long storeId) {
        return new ManagedStoreResponse(
                Long.toString(storeId),
                "미리윰",
                Region.SEOUL,
                "서울시 중구",
                "Asia/Seoul",
                "CAFE_BAKERY",
                VerificationStatus.APPROVED,
                OperationStatus.OPEN,
                PickupEligibility.ELIGIBLE,
                new StoreModesRequest(true, true, true),
                new StoreGeocodingResponse(
                        GeocodingStatus.VERIFIED,
                        new BigDecimal("37.566826000000000"),
                        new BigDecimal("126.978656700000000"),
                        "서울 중구 세종대로 110",
                        Instant.parse("2026-08-04T09:00:00Z"),
                        1L));
    }
}
