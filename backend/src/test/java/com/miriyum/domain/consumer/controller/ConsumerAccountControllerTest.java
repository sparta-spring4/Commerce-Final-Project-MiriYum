package com.miriyum.domain.consumer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 일반 사용자 마이페이지 API가 {@code C-013}의 상태 코드 판정을 HTTP 응답 수준에서 지키는지
 * 확인한다. 매장 운영자 쪽과 같은 기준으로 동작해야 하므로 같은 케이스를 양쪽에 둔다(이슈 #72).
 *
 * <p>멱등 기반(#32)은 {@code INSERT IGNORE}·{@code SELECT ... FOR UPDATE}로 선점을 판정하는
 * MySQL 전용 구현이므로 H2가 아니라 Testcontainers MySQL을 사용한다
 * ({@code docs/service-policies/18-scale-reliability.md} SCALE-014, 이슈 #63).</p>
 */
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.identity-verification.dev-stub-enabled=false"
        })
@AutoConfigureMockMvc
class ConsumerAccountControllerTest {

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
    private ConsumerAccountRepository consumerAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long accountId;

    @BeforeEach
    void setUp() {
        // 컨테이너는 클래스 전체에서 공유되므로 계정·멱등 기록을 매 테스트마다 초기화한다.
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        consumerAccountRepository.deleteAll();
        consumerAccountRepository.flush();
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "이전닉네임");
        accountId = consumerAccountRepository.saveAndFlush(account).getId();
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
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, accountId);
        consumerAccountRepository.deleteAll();
        consumerAccountRepository.flush();

        String deletedAccountBody = mockMvc.perform(get("/api/v1/consumer-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String invalidTokenBody = mockMvc.perform(get("/api/v1/consumer-accounts/me")
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
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, accountId);
        jdbcTemplate.update(
                "UPDATE consumer_accounts SET status = 'SUSPENDED' WHERE consumer_account_id = ?", accountId);

        mockMvc.perform(get("/api/v1/consumer-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_011"));
    }

    /**
     * 멱등 재생은 저장된 결과를 그대로 돌려주므로, 계정 확인이 업무 콜백 안에 있으면 계정이 사라진
     * 뒤에도 최초 200이 재생돼 C-013 판정을 우회한다. 인증 경계를 멱등 실행기 앞으로 옮긴 뒤의
     * 회귀를 HTTP 수준에서 고정한다(PR #78 리뷰 지적).
     */
    @Test
    @DisplayName("수정 성공 후 계정이 사라지면 같은 Idempotency-Key 재요청도 401 AUTH_003을 반환한다")
    void updateMeDoesNotReplayStoredResultAfterAccountDisappears() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, accountId);
        mockMvc.perform(patch("/api/v1/consumer-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"새닉네임\"}"))
                .andExpect(status().isOk());

        consumerAccountRepository.deleteAll();
        consumerAccountRepository.flush();

        // 같은 키·같은 본문이라 멱등 기록은 그대로 남아 있지만, 인증 경계가 먼저 걸린다.
        mockMvc.perform(patch("/api/v1/consumer-accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"새닉네임\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"));
    }
}
