package com.miriyum.domain.storeoperator.controller.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 매장 운영자 마이페이지 API가 namespace 분리와 C-006 멱등 계약을 실제 HTTP 응답 수준에서
 * 지키는지 확인한다.
 *
 * <p>멱등 기반(#32)은 {@code INSERT IGNORE}·{@code SELECT ... FOR UPDATE}로 선점을 판정하는
 * MySQL 전용 구현이므로 H2가 아니라 Testcontainers MySQL을 사용한다
 * ({@code docs/service-policies/18-scale-reliability.md} SCALE-014, 이슈 #63).</p>
 */
@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
@AutoConfigureMockMvc
class StoreOperatorAccountControllerTest {

    private static final String VALID_IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private StoreOperatorAccountRepository storeOperatorAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long accountId;

    @BeforeEach
    void setUp() {
        // 컨테이너는 클래스 전체에서 공유되므로 계정·멱등 기록을 매 테스트마다 초기화한다.
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        storeOperatorAccountRepository.deleteAll();
        storeOperatorAccountRepository.flush();
        StoreOperatorAccount account = StoreOperatorAccount.create("owner@example.com", "hashed", "미리윰식당");
        accountId = storeOperatorAccountRepository.saveAndFlush(account).getId();
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
                .andExpect(jsonPath("$.data.displayName").value("미리윰식당"))
                // phoneNumber는 필드가 빠진 게 아니라 값이 명시적으로 null이어야 한다. jsonPath의
                // doesNotExist()는 값이 null이면 통과해 두 경우를 구분하지 못하므로, 키 존재와
                // null 값을 따로 검증해 OpenAPI의 nullable 계약을 고정한다.
                .andExpect(jsonPath("$.data").value(hasKey("phoneNumber")))
                .andExpect(jsonPath("$.data.phoneNumber").value(nullValue()));
    }

    /**
     * C-013은 "subject에 해당하는 현재 계정을 확인할 수 없음"을 401로 정한다. 계정이 사라진 뒤에도
     * Access Token은 최대 1시간 살아 있으므로 이 경로가 실제로 열린다(이슈 #72).
     *
     * <p>응답이 잘못된 토큰의 401과 완전히 같아야 계정 삭제 여부가 드러나지 않는다. ErrorResponse는
     * {@code code}·{@code message}만 담고 시각·경로 같은 변동 필드가 없어 본문을 그대로 비교한다.</p>
     */
    @Test
    @DisplayName("계정이 사라진 뒤 유효한 토큰으로 조회하면 잘못된 토큰과 똑같은 401 AUTH_003을 반환한다")
    void getMeWithDeletedAccountIsIndistinguishableFromInvalidToken() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);
        storeOperatorAccountRepository.deleteAll();
        storeOperatorAccountRepository.flush();

        String deletedAccountBody = mockMvc.perform(get("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String invalidTokenBody = mockMvc.perform(get("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(deletedAccountBody).isEqualTo(invalidTokenBody);
    }

    @Test
    @DisplayName("계정이 비활성이면 계정 부재와 달리 403과 AUTH_011을 반환한다")
    void getMeWithSuspendedAccountReturnsForbidden() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);
        jdbcTemplate.update(
                "UPDATE store_operator_accounts SET status = 'SUSPENDED' WHERE store_operator_account_id = ?",
                accountId);

        mockMvc.perform(get("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_011"));
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

    @Test
    @DisplayName("인증된 매장 운영자는 최초 연락처를 등록하고 마스킹된 번호를 받는다")
    void registerContactReturnsMaskedPhoneNumber() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);

        mockMvc.perform(put("/api/v1/store-operator-accounts/me/contact")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"010-1234-5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").value("010-****-5678"));
    }

    @Test
    @DisplayName("삭제된 계정은 잘못된 연락처 형식보다 먼저 401 AUTH_003으로 거절한다")
    void registerContactWithDeletedAccountTakesPrecedenceOverInvalidPhone() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);
        storeOperatorAccountRepository.deleteAll();
        storeOperatorAccountRepository.flush();

        mockMvc.perform(put("/api/v1/store-operator-accounts/me/contact")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"invalid\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"));
    }

    @Test
    @DisplayName("정규화된 같은 연락처는 같은 Idempotency-Key로 재요청해도 결과를 재생한다")
    void registerContactReplaysForEquivalentPhoneFormatting() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);

        mockMvc.perform(put("/api/v1/store-operator-accounts/me/contact")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"010-1234-5678\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/store-operator-accounts/me/contact")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\": \"01012345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").value("010-****-5678"));
    }

    @Test
    @DisplayName("동시에 최초 연락처를 등록하면 한 요청만 성공하고 다른 요청은 ACCOUNT_007을 반환한다")
    void registerContactSerializesConcurrentFirstRegistration() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> mockMvc.perform(
                    put("/api/v1/store-operator-accounts/me/contact")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("Idempotency-Key", "550e8400-e29b-41d4-a716-446655440003")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phoneNumber\": \"010-1111-1111\"}"))
                    .andReturn());
            Future<MvcResult> second = executor.submit(() -> mockMvc.perform(
                    put("/api/v1/store-operator-accounts/me/contact")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("Idempotency-Key", "550e8400-e29b-41d4-a716-446655440004")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phoneNumber\": \"010-2222-2222\"}"))
                    .andReturn());

            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();
            assertThat(List.of(
                    firstResult.getResponse().getStatus(),
                    secondResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
            String conflictBody = firstResult.getResponse().getStatus() == 409
                    ? firstResult.getResponse().getContentAsString()
                    : secondResult.getResponse().getContentAsString();
            assertThat(conflictBody).contains("ACCOUNT_007");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 같은 요청을 다시 보내면 최초 결과를 재생한다")
    void updateMeReplaysStoredResultForSameKeyAndBody() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch("/api/v1/store-operator-accounts/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\": \"첫상호명\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.displayName").value("첫상호명"));
        }

        // 두 번째 요청은 저장된 결과를 재생만 하므로 멱등 기록은 하나만 남는다.
        assertSingleSucceededIdempotencyRecord();
    }

    @Test
    @DisplayName("같은 Idempotency-Key를 다른 본문으로 재사용하면 409와 COMMON_007을 반환한다")
    void updateMeRejectsSameKeyWithDifferentBody() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);
        mockMvc.perform(patch("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"첫상호명\"}"))
                .andExpect(status().isOk());

        // when & then: 같은 키에 다른 입력을 실으면 두 번째 요청을 실행하지 않고 거절한다
        mockMvc.perform(patch("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"다른상호명\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON_007"));

        // then: 최초 결과가 덮어써지지 않았는지 확인한다
        mockMvc.perform(get("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.displayName").value("첫상호명"));
    }

    /**
     * 멱등 재생은 저장된 결과를 그대로 돌려주므로, 계정 확인이 업무 콜백 안에 있으면 계정이 사라진
     * 뒤에도 최초 200이 재생돼 C-013 판정을 우회한다. 인증 경계를 멱등 실행기 앞으로 옮긴 뒤의
     * 회귀를 HTTP 수준에서 고정한다(PR #78 리뷰 지적).
     */
    @Test
    @DisplayName("수정 성공 후 계정이 사라지면 같은 Idempotency-Key 재요청도 401 AUTH_003을 반환한다")
    void updateMeDoesNotReplayStoredResultAfterAccountDisappears() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);
        mockMvc.perform(patch("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"첫상호명\"}"))
                .andExpect(status().isOk());

        storeOperatorAccountRepository.deleteAll();
        storeOperatorAccountRepository.flush();

        // 같은 키·같은 본문이라 멱등 기록은 그대로 남아 있지만, 인증 경계가 먼저 걸린다.
        mockMvc.perform(patch("/api/v1/store-operator-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"첫상호명\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"));
    }

    private void assertSingleSucceededIdempotencyRecord() {
        Integer succeededCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands WHERE processing_status = 'SUCCEEDED'", Integer.class);
        assertThat(succeededCount).isEqualTo(1);
    }
}
