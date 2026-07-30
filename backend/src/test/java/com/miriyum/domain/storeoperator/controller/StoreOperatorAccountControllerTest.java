package com.miriyum.domain.storeoperator.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 매장 운영자 마이페이지 API가 namespace 분리·멱등성 키 요구를 실제 HTTP 응답 수준에서 지키는지 확인한다.
 */
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:store-operator-account-controller-test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.identity-verification.dev-stub-enabled=false"
        })
@AutoConfigureMockMvc
class StoreOperatorAccountControllerTest {

    private static final String VALID_IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    private Long accountId;

    @BeforeEach
    void setUp() {
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        accountId = storeOperatorAccountRepository.save(account).getId();
    }

    @Test
    @DisplayName("Access Token 없이 조회하면 401과 AUTH_001을 반환한다")
    void getMeWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/store-operator-accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test
    @DisplayName("일반 사용자 namespace 토큰으로 조회하면 401과 AUTH_004를 반환한다")
    void getMeWithConsumerTokenReturnsNamespaceMismatch() throws Exception {
        String consumerToken = jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, accountId);

        mockMvc.perform(get("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + consumerToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
    }

    @Test
    @DisplayName("매장 운영자 토큰으로 본인 정보를 조회한다")
    void getMeReturnsAccount() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);

        mockMvc.perform(get("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("미리윰식당"));
    }

    @Test
    @DisplayName("Idempotency-Key 없이 수정하면 400과 COMMON_003을 반환한다")
    void updateMeWithoutIdempotencyKeyReturnsBadRequest() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);

        mockMvc.perform(patch("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"새상호명\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("매장 운영자 토큰과 Idempotency-Key로 상호명을 수정한다")
    void updateMeChangesDisplayName() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);

        mockMvc.perform(patch("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"새상호명\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("새상호명"));
    }
}
