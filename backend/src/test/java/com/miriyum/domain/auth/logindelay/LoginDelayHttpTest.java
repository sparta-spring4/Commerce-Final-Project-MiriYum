package com.miriyum.domain.auth.logindelay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 계정 단위 로그인 지연(AUTH-006)이 실제 HTTP 응답 수준에서도 적용되는지 확인한다.
 *
 * <p>{@code LoginDelayIntegrationTest}는 Service를 직접 호출해 상태 전이를 검증한다. 여기서는
 * 컨트롤러·필터체인을 모두 거친 실제 요청에서 지연이 평소 실패와 같은 {@code AUTH_005}로
 * 나가는지를 본다.</p>
 *
 * <p>로그인 IP 요청 제한 기본값은 10분에 5회라, 그대로 두면 6번째 요청이 계정 지연에 닿기 전에
 * {@code RateLimitFilter}에서 429로 막힌다. 두 방어는 중첩 적용되는 게 정상이지만 이 테스트가
 * 확인하려는 건 계정 단위 지연이므로, IP 한도만 넉넉히 올려 계정 지연을 격리해서 검증한다.</p>
 */
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.rate-limit.login.max-requests=100"
        })
@AutoConfigureMockMvc
class LoginDelayHttpTest {

    private static final String EMAIL = "http-delay@example.com";
    private static final String RAW_PASSWORD = "Password123!";
    private static final String WRONG_PASSWORD = "WrongPassword123!";

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
    private ConsumerAccountRepository consumerAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM login_failure_delays");
        jdbcTemplate.execute("DELETE FROM rate_limit_windows");
        consumerAccountRepository.deleteAll();
        consumerAccountRepository.flush();
        consumerAccountRepository.saveAndFlush(
                ConsumerAccount.create(EMAIL, passwordEncoder.encode(RAW_PASSWORD), "지연테스트"));
    }

    @Test
    @DisplayName("연속 5회 실패 뒤에는 올바른 비밀번호로 요청해도 401 AUTH_005를 반환한다")
    void sixthRequestIsRejectedWithSameErrorEvenWithCorrectPassword() throws Exception {
        // given: 실패 5회를 실제 HTTP 요청으로 쌓는다
        for (int attempt = 0; attempt < 5; attempt++) {
            login(WRONG_PASSWORD)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_005"));
        }

        // when & then: 지연 중이라 비밀번호가 맞아도 같은 응답으로 거절된다
        login(RAW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_005"));
    }

    @Test
    @DisplayName("4회까지는 지연되지 않아 올바른 비밀번호로 로그인할 수 있다")
    void loginStillSucceedsBeforeThreshold() throws Exception {
        // given: 지연 직전까지만 실패
        for (int attempt = 0; attempt < 4; attempt++) {
            login(WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }

        // when & then
        login(RAW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("로그인에 성공하면 실패 횟수가 초기화돼 다시 5회를 채워야 지연된다")
    void successResetsFailureCountOverHttp() throws Exception {
        // given: 4회 실패 후 성공으로 초기화
        for (int attempt = 0; attempt < 4; attempt++) {
            login(WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }
        login(RAW_PASSWORD).andExpect(status().isOk());

        // when: 다시 4회 실패해도
        for (int attempt = 0; attempt < 4; attempt++) {
            login(WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }

        // then: 초기화됐으므로 아직 지연 전이라 로그인에 성공한다
        login(RAW_PASSWORD).andExpect(status().isOk());
    }

    /** 테스트 계정으로 로그인을 시도한다. 지연은 계정 단위라 대상 계정은 항상 같다. */
    private ResultActions login(String password) throws Exception {
        String requestBody = """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(EMAIL, password);
        return mockMvc.perform(post("/api/v1/consumers/auth/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody));
    }
}
