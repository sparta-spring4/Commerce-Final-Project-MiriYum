package com.miriyum.domain.store.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * catalog 공개 경로군의 실제 SecurityFilterChain 동작을 검증한다.
 *
 * <p>정확한 세 GET 경로만 익명 200(SUCCESS envelope)이고, 같은 경로군의 유사 경로·다른 method는 catalog
 * 전용 체인 안에서 401(AUTH_001)/403(AUTH_006) 공통 ErrorResponse envelope로 처리된다(global default
 * 체인으로 새지 않는다). 1번 SecurityConfig는 수정하지 않는다. ADR-004에 따라 H2가 아니라 Testcontainers
 * MySQL로 실제 컨텍스트를 띄운다.</p>
 */
@SpringBootTest(
        classes = com.miriyum.MiriyumApplication.class,
        properties = {
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.jwt.issuer=miriyum"
        })
@AutoConfigureMockMvc
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class CatalogSecurityIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String consumerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, 1L);
    }

    private String storeOperatorToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, 1L);
    }

    @Test
    @DisplayName("정확한 세 익명 GET은 200과 SUCCESS envelope를 반환한다")
    void anonymousGet_exactPublicPaths_return200WithSuccessEnvelope() throws Exception {
        // when & then
        for (String path : new String[] {
                "/api/v1/store-categories", "/api/v1/menu-categories", "/api/v1/store-tags"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items").isArray());
        }
    }

    @Test
    @DisplayName("익명 유사 경로는 401 AUTH_001 공통 envelope(JSON)를 반환한다")
    void anonymousSimilarPath_returns401AuthErrorEnvelope() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/store-categories/extra"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_001"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("인증된 유사 경로는 403 AUTH_006 공통 envelope(JSON)를 반환한다")
    void authenticatedSimilarPath_returns403AuthErrorEnvelope() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/menu-categories/extra")
                        .header(HttpHeaders.AUTHORIZATION, consumerToken()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_006"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("정확한 경로의 허용되지 않은 method는 익명 401·인증 403 공통 envelope다")
    void disallowedMethodOnExactPath_anon401_authenticated403() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/store-categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        mockMvc.perform(post("/api/v1/store-categories")
                        .header(HttpHeaders.AUTHORIZATION, consumerToken()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_006"));
    }

    @Test
    @DisplayName("잘못된 토큰이나 다른 namespace 토큰이 있어도 정확한 공개 GET은 200이다")
    void publicGet_withInvalidOrForeignToken_stillReturns200() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/store-categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/store-categories")
                        .header(HttpHeaders.AUTHORIZATION, storeOperatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
