package com.miriyum.domain.store.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * catalog 공개 경로의 실제 SecurityFilterChain 동작을 검증한다.
 *
 * <p>정확한 세 GET 경로만 익명 200, 그 밖의 method·요청은 공통 ErrorResponse envelope로 401/403.
 * 1번 SecurityConfig를 수정하지 않고 store 도메인 전용 체인으로 완결하는지 확인한다. ADR-004에 따라
 * H2가 아니라 Testcontainers MySQL로 실제 컨텍스트를 띄운다.</p>
 */
@SpringBootTest(
        classes = com.miriyum.MiriyumApplication.class,
        properties = {
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.jwt.issuer=miriyum"
        })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CatalogSecurityIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("익명 GET으로 세 catalog 경로가 200을 반환한다")
    void anonymousGet_publicCatalogPaths_return200() throws Exception {
        mockMvc.perform(get("/api/v1/store-categories")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/menu-categories")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/store-tags")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("익명 차단 요청(허용되지 않은 method)은 401 공통 envelope다")
    void anonymousDeniedRequest_returns401WithCommonEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/store-categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("인증된 권한 부족 요청은 403 공통 envelope다")
    void authenticatedForbiddenRequest_returns403WithCommonEnvelope() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, 1L);

        mockMvc.perform(post("/api/v1/store-categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("유사 경로나 다른 method가 실수로 공개되지 않는다")
    void similarPathOrOtherMethod_isNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/store-categories/extra"))
                .andExpect(status().is(not2xx()));
        mockMvc.perform(post("/api/v1/menu-categories")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, 1L)))
                .andExpect(status().isForbidden());
    }

    private static org.hamcrest.Matcher<Integer> not2xx() {
        return org.hamcrest.Matchers.not(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.greaterThanOrEqualTo(200),
                org.hamcrest.Matchers.lessThan(300)));
    }
}
