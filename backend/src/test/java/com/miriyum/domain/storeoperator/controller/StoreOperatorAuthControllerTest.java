package com.miriyum.domain.storeoperator.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
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
 * 회원가입 API가 실제 HTTP 응답 수준에서 본인확인 스텁 경계를 지키는지 확인한다.
 *
 * <p>가입 요청은 {@code RateLimitFilter}를 거치며 MySQL 전용 원자적 upsert 문법을 쓰므로
 * H2가 아니라 Testcontainers MySQL을 사용한다({@code docs/service-policies/18-scale-reliability.md}
 * SCALE-014, 이슈 #63).</p>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.identity-verification.dev-stub-enabled=false"
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
    @DisplayName("본인확인 스텁이 꺼져 있으면 회원가입 API는 503과 COMMON_012를 반환한다")
    void signUpReturnsServiceUnavailableWhenIdentityVerificationStubDisabled() throws Exception {
        // given
        String requestBody = """
                {
                  "email": "owner@example.com",
                  "password": "password123",
                  "passwordConfirm": "password123",
                  "emailVerificationReference": "email-ref",
                  "identityVerificationReference": "identity-ref",
                  "displayName": "미리윰식당"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/v1/store-operator-auth/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_012"));
    }
}
