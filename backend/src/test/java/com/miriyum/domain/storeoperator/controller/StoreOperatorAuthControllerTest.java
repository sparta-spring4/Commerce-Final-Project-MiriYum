package com.miriyum.domain.storeoperator.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 회원가입 API가 1차 MVP의 직접 입력 연락처 계약을 지키는지 확인한다.
 *
 * <p>가입 요청은 {@code RateLimitFilter}를 거치며 MySQL 전용 원자적 upsert 문법을 쓰므로
 * H2가 아니라 Testcontainers MySQL을 사용한다({@code docs/service-policies/18-scale-reliability.md}
 * SCALE-014, 이슈 #63).</p>
 */
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
@AutoConfigureMockMvc
class StoreOperatorAuthControllerTest {

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

    @Test
    @DisplayName("직접 입력한 MVP 신뢰 연락처로 회원가입하면 201을 반환한다")
    void signUpSucceedsWithMvpTrustedContact() throws Exception {
        // given
        String requestBody = """
                {
                  "email": "owner@example.com",
                  "password": "Password123!",
                  "passwordConfirm": "Password123!",
                  "phoneNumber": "010-1234-5678",
                  "displayName": "미리윰식당"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/v1/store-operator-auth/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountType").value("STORE_OPERATOR"));
    }

    @Test
    @DisplayName("Refresh 쿠키 없이도 CSRF 검증을 통과하면 로그아웃이 성공한다")
    void logoutSucceedsWithoutRefreshCookie() throws Exception {
        String csrfToken = "store-operator-logout-csrf-token";

        mockMvc.perform(delete("/api/v1/store-operator-auth/sessions/current")
                        .cookie(new Cookie("MIRIYUM_STORE_OPERATOR_XSRF_TOKEN", csrfToken))
                        .header("X-CSRF-TOKEN", csrfToken))
                .andExpect(status().isOk());
    }
}
