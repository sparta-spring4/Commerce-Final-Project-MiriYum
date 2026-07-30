package com.miriyum.domain.consumer.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 회원가입 API가 실제 HTTP 응답 수준에서 본인확인 스텁 경계를 지키는지 확인한다.
 */
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:consumer-auth-controller-test;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.identity-verification.dev-stub-enabled=false"
        })
@AutoConfigureMockMvc
class ConsumerAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("본인확인 스텁이 꺼져 있으면 회원가입 API는 503과 COMMON_012를 반환한다")
    void signUpReturnsServiceUnavailableWhenIdentityVerificationStubDisabled() throws Exception {
        // given
        String requestBody = """
                {
                  "email": "user@example.com",
                  "password": "password123",
                  "passwordConfirm": "password123",
                  "emailVerificationReference": "email-ref",
                  "identityVerificationReference": "identity-ref",
                  "nickname": "닉네임"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/v1/consumer-auth/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_012"));
    }
}
