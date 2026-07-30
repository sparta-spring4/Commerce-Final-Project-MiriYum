package com.miriyum.domain.store.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
import com.miriyum.domain.store.core.dto.StoreModesRequest;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.core.service.StoreCommandResult;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import org.junit.jupiter.api.Test;
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
                .andExpect(jsonPath("$.data.storeId").value(7))
                .andExpect(jsonPath("$.data.operationStatus").value("OPEN"));
    }

    @Test
    void getReturnsManagedStoreEnvelope() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.getManagedStore(11L, 7L)).willReturn(managedStore(7L));

        mockMvc.perform(get("/api/v1/store-operator/stores/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").value(7));
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
                .andExpect(jsonPath("$.data.storeId").value(7));
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
        return """
                {
                  "businessRegistrationNumber": "1234567890",
                  "businessType": "CAFE",
                  "name": "미리윰",
                  "description": "",
                  "region": "SEOUL",
                  "address": "서울시 중구",
                  "storeCategoryCode": "CAFE_BAKERY",
                  "tagCodes": ["DATE"],
                  "modes": {
                    "reservationEnabled": true,
                    "menuHoldEnabled": true,
                    "pickupEnabled": true
                  }
                }
                """;
    }

    private ManagedStoreResponse managedStore(long storeId) {
        return new ManagedStoreResponse(
                storeId,
                "미리윰",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                VerificationStatus.APPROVED,
                OperationStatus.OPEN,
                PickupEligibility.ELIGIBLE,
                new StoreModesRequest(true, true, true));
    }
}
