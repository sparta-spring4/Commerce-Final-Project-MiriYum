package com.miriyum.domain.consumer.controller.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * 회원가입 API가 1차 MVP의 직접 입력 연락처 계약을 지키는지 확인한다.
 *
 * <p>가입 요청은 {@code RateLimitFilter}를 거치며 MySQL 전용 원자적 upsert 문법을 쓰므로
 * H2가 아니라 Testcontainers MySQL을 사용한다({@code docs/service-policies/18-scale-reliability.md}
 * SCALE-014, 이슈 #63).</p>
 */
@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.kakao.enabled=true",
            "miriyum.kakao.rest-api-key=test-kakao-rest-api-key",
            "miriyum.kakao.client-secret=test-kakao-client-secret",
            "miriyum.kakao.redirect-uris=https://app.example.com/auth/kakao/callback"
        })
@AutoConfigureMockMvc
class ConsumerAuthControllerTest {

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
                  "email": "user@example.com",
                  "password": "Password123!",
                  "passwordConfirm": "Password123!",
                  "phoneNumber": "010-1234-5678",
                  "ageConfirmed": true,
                  "nickname": "닉네임"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/v1/consumers/auth/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountType").value("CONSUMER"));
    }

    @Test
    @DisplayName("카카오 인가 주소를 발급하면 같은 브라우저 검증용 state 쿠키를 설정한다")
    void createsKakaoAuthorizationWithStateCookie() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/consumer-auth/kakao/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "redirectUri": "https://app.example.com/auth/kakao/callback" }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString(
                        "MIRIYUM_CONSUMER_KAKAO_LOGIN_STATE=")))
                .andExpect(jsonPath("$.data.authorizationUrl").exists());
    }

    @Test
    @DisplayName("카카오 state 쿠키 없이 콜백을 보내면 외부 카카오 호출 전에 거절한다")
    void rejectsKakaoCallbackWithoutStateCookie() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/consumer-auth/kakao/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationCode": "unused-code",
                                  "state": "untrusted-state",
                                  "redirectUri": "https://app.example.com/auth/kakao/callback"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_013"));
    }
}
