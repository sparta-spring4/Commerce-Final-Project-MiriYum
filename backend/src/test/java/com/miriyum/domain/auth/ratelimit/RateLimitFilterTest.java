package com.miriyum.domain.auth.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 요청 제한 필터가 실제 HTTP 응답 수준에서, 그리고 실제 MySQL(Testcontainers) 위에서
 * 동작하는지 확인한다. H2는 {@link RateLimitWindowRepository}의 원자적 upsert 문법
 * (MySQL 전용 {@code ON DUPLICATE KEY UPDATE})을 지원하지 않아 증거로 쓰지 않는다
 * ({@code docs/service-policies/18-scale-reliability.md} SCALE-014).
 */
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.rate-limit.sign-up.max-requests=5",
            "miriyum.rate-limit.sign-up.window-seconds=600",
            "miriyum.rate-limit.login.max-requests=5",
            "miriyum.rate-limit.login.window-seconds=600",
            "miriyum.rate-limit.token-refresh.max-requests=30",
            "miriyum.rate-limit.token-refresh.window-seconds=60",
            "miriyum.rate-limit.csrf-preparation.max-requests=2",
            "miriyum.rate-limit.csrf-preparation.window-seconds=60"
        })
@AutoConfigureMockMvc
class RateLimitFilterTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("한도를 초과한 요청은 429와 Retry-After를 반환한다")
    void blocksRequestsOverTheLimit() throws Exception {
        // given: CSRF 준비 한도(2회)만큼 먼저 소비
        RequestPostProcessor ip = withRemoteAddr("10.0.0.1");
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current").with(ip)).andExpect(status().isOk());

        // when & then
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current").with(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_010"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("제한 대상이 아닌 경로(로그아웃)는 요청 제한을 받지 않는다")
    void doesNotLimitUnlistedEndpoints() throws Exception {
        // 로그아웃은 목록에 없으므로 몇 번을 호출해도 429가 아니라(401/403 등) 다른 결과여야 한다.
        RequestPostProcessor ip = withRemoteAddr("10.0.0.3");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(delete("/api/v1/consumer-auth/sessions/current").with(ip))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        if (status == 429) {
                            throw new AssertionError("로그아웃은 요청 제한 대상이 아니어야 하는데 429가 반환됐습니다.");
                        }
                    });
        }
    }

    @Test
    @DisplayName("서로 다른 등급(회원가입/CSRF 준비)은 독립적으로 카운트한다")
    void tracksDifferentCategoriesIndependently() throws Exception {
        // given: CSRF 준비 한도(2회)를 다 소비해도
        RequestPostProcessor ip = withRemoteAddr("10.0.0.2");
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consumer-auth/csrf-tokens/current").with(ip))
                .andExpect(status().isTooManyRequests());

        // when & then: 다른 등급(토큰 재발급)은 영향받지 않는다
        mockMvc.perform(post("/api/v1/consumer-auth/token-refreshes").with(ip))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 429) {
                        throw new AssertionError("토큰 재발급은 CSRF 준비와 별개 등급이라 429를 받으면 안 됩니다.");
                    }
                });
    }

    private static RequestPostProcessor withRemoteAddr(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }
}
