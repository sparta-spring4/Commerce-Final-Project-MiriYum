package com.miriyum.domain.store.controller.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.store.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;
import com.miriyum.domain.store.dto.storeoperator.StoreGeocodingResponse;
import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreModesRequest;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.service.StoreCommandResult;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.onboarding.service.StoreOnboardingSubmissionService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(StoreController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class StoreControllerTest {

    private static final String TEST_KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private StoreOnboardingSubmissionService onboardingSubmissionService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void missingBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/store-operators/stores/7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test
    void consumerTokenReturnsNamespaceMismatch() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 11L));

        mockMvc.perform(get("/api/v1/store-operators/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
    }

    @Test
    void legacyJsonCreateIsRejected() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.create(eq(11L), any(IdempotencyKey.class), any()))
                .willReturn(new StoreCommandResult(201, managedStore(7L)));

        mockMvc.perform(post("/api/v1/store-operators/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_009"));
    }

    @Test
    void createAcceptsMultipartEvidenceAndReturnsApplication() throws Exception {
        authenticateStoreOperator(11L);
        var data = new ObjectMapper().createObjectNode()
                .put("applicationId", "41")
                .put("applicationVersion", 1)
                .put("status", "AUTO_CHECKING")
                .put("reviewRequired", true)
                .put("nextAction", "WAIT");
        given(onboardingSubmissionService.submit(
                eq(11L), any(IdempotencyKey.class), any(), any()))
                .willReturn(new IdempotentOutcome(
                        false, 202, "SUCCESS", "STORE_ONBOARDING_APPLICATION", "41", data));
        MockMultipartFile application = new MockMultipartFile(
                "application", "", MediaType.APPLICATION_JSON_VALUE,
                validCreateJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile evidence = new MockMultipartFile(
                "businessRegistrationEvidence", "license.pdf", "application/pdf",
                "%PDF-1.7\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/store-operators/stores")
                        .file(application)
                        .file(evidence)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.applicationVersion").value(1))
                .andExpect(jsonPath("$.data.status").value("AUTO_CHECKING"))
                .andExpect(jsonPath("$.data.nextAction").value("WAIT"))
                .andExpect(jsonPath("$.data.storeId").doesNotExist());
    }

    @Test
    void createAcceptsDeprecatedBusinessTypeFromLegacyClient() throws Exception {
        authenticateStoreOperator(11L);
        var data = new ObjectMapper().createObjectNode()
                .put("applicationId", "41")
                .put("applicationVersion", 1)
                .put("status", "AUTO_CHECKING")
                .put("reviewRequired", true)
                .put("nextAction", "WAIT");
        given(onboardingSubmissionService.submit(
                eq(11L), any(IdempotencyKey.class), any(), any()))
                .willReturn(new IdempotentOutcome(
                        false, 202, "SUCCESS", "STORE_ONBOARDING_APPLICATION", "41", data));
        String legacyJson = validCreateJson().replace(
                "\"businessRegistrationNumber\": \"1234567890\",",
                "\"businessRegistrationNumber\": \"1234567890\",\n"
                        + "  \"businessType\": \"CAFE\",");
        MockMultipartFile application = new MockMultipartFile(
                "application", "", MediaType.APPLICATION_JSON_VALUE,
                legacyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile evidence = new MockMultipartFile(
                "businessRegistrationEvidence", "license.pdf", "application/pdf",
                "%PDF-1.7\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/store-operators/stores")
                        .file(application)
                        .file(evidence)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY))
                .andExpect(status().isAccepted());

        ArgumentCaptor<StoreCreateRequest> request =
                ArgumentCaptor.forClass(StoreCreateRequest.class);
        then(onboardingSubmissionService).should().submit(
                eq(11L), any(IdempotencyKey.class), request.capture(), any());
        assertThat(request.getValue().compatibilityBusinessType())
                .isEqualTo(BusinessType.CAFE);
    }

    @Test
    void createReturnsBadRequestWhenGeocodingResultDoesNotMatch() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.create(eq(11L), any(IdempotencyKey.class), any()))
                .willThrow(new ServiceException(CommonErrorCode.VALIDATION_FAILED));

        mockMvc.perform(post("/api/v1/store-operators/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_009"));
    }

    @Test
    void createRejectsUnknownIanaTimeZone() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operators/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson().replace(
                                "Asia/Seoul",
                                "Mars/Olympus")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_009"));

        then(storeService).shouldHaveNoInteractions();
    }

    @Test
    void createRejectsMissingOnboardingDeclarations() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operators/stores")
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
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_009"));

        then(storeService).shouldHaveNoInteractions();
    }

    @Test
    void createRejectsFalseRequiredTermsAgreement() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operators/stores")
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
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_009"));

        then(storeService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveStoreIdRejectsGetBeforeService(long storeId)
            throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(get("/api/v1/store-operators/stores/{storeId}", storeId)
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

        mockMvc.perform(get("/api/v1/store-operators/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").isString())
                .andExpect(jsonPath("$.data.storeId").value("7"))
                .andExpect(jsonPath("$.data.geocoding.status").value("VERIFIED"));
    }

    @Test
    void listReturnsCurrentOperatorsManagedStores() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.getManagedStores(11L))
                .willReturn(List.of(managedStore(7L), managedStore(9L)));

        mockMvc.perform(get("/api/v1/store-operators/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].storeId").value("7"))
                .andExpect(jsonPath("$.data[1].storeId").value("9"));
    }

    @Test
    void listReturnsEmptyArrayWhenCurrentOperatorOwnsNoStores() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.getManagedStores(11L)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/store-operators/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveStoreIdRejectsPatchBeforeService(long storeId)
            throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(patch("/api/v1/store-operators/stores/{storeId}", storeId)
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

        mockMvc.perform(patch("/api/v1/store-operators/stores/7")
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

        mockMvc.perform(patch("/api/v1/store-operators/stores/7")
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

        mockMvc.perform(patch("/api/v1/store-operators/stores/7")
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

        mockMvc.perform(post("/api/v1/store-operators/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJsonWithModes(modesJson)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_009"));
    }

    @ParameterizedTest
    @MethodSource("modeObjectsMissingOneRequiredField")
    void patchRejectsMissingModeField(String modesJson) throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(patch("/api/v1/store-operators/stores/7")
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

        mockMvc.perform(post("/api/v1/store-operators/stores")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_009"));
    }

    @Test
    void otherOperatorReturnsStore003() throws Exception {
        authenticateStoreOperator(12L);
        given(storeService.getManagedStore(12L, 7L))
                .willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED));

        mockMvc.perform(get("/api/v1/store-operators/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    @Test
    void missingStoreReturnsStore001() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.getManagedStore(11L, 7L))
                .willThrow(new ServiceException(StoreErrorCode.STORE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/store-operators/stores/7")
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
                  "name": "미리윰",
                  "description": "",
                  "region": "SEOUL",
                  "address": "서울시 중구",
                  "timeZoneId": "Asia/Seoul",
                  "storeCategoryCode": "CAFE_BAKERY",
                  "tagCodes": ["DATE"],
                  "modes": %s%s
                  ,"legalBusinessName": "미리윰 주식회사",
                  "representativeName": "김대표",
                  "openingDate": "2026-08-21",
                  "primaryBusinessCategory": "음식점업",
                  "primaryBusinessItem": "카페"
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
