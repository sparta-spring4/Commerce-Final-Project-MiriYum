package com.miriyum.domain.platformoperator.controller.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import java.time.Instant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.platform-operator.enabled=true",
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3"
})
@AutoConfigureMockMvc
@Import(PlatformOperatorAuthHttpIT.BusinessController.class)
class PlatformOperatorAuthHttpIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");
    @Container static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        accounts.deleteAll();
        accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "operator@example.com", passwordEncoder.encode("Password1!"), "operator", Instant.now().plusSeconds(600)));
    }

    @Test
    void completesLoginInitialChangeRefreshLogoutAndPermissionFlow() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/platform-operators/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"operator@example.com\",\"password\":\"Password1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();

        assertThat(login.getResponse().getHeader("Set-Cookie"))
                .contains("MIRIYUM_PLATFORM_OPERATOR_REFRESH=")
                .contains("Path=/api/v1/platform-operators/auth")
                .contains("HttpOnly");
        String access = com.jayway.jsonpath.JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");
        Cookie limitedRefresh = login.getResponse().getCookie("MIRIYUM_PLATFORM_OPERATOR_REFRESH");
        mvc.perform(get("/api/v1/platform-operators/test-business").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_012"));

        MvcResult changed = mvc.perform(put("/api/v1/platform-operators/auth/initial-password")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Password1!","newPassword":"Changed2@",
                                 "newPasswordConfirm":"Changed2@"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(false))
                .andReturn();
        String activeAccess = com.jayway.jsonpath.JsonPath.read(
                changed.getResponse().getContentAsString(), "$.data.accessToken");
        Cookie activeRefresh = changed.getResponse().getCookie("MIRIYUM_PLATFORM_OPERATOR_REFRESH");
        mvc.perform(get("/api/v1/platform-operators/test-business")
                        .header("Authorization", "Bearer " + activeAccess))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/platform-operators/test-business").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_015"));

        MvcResult refreshed = mvc.perform(post("/api/v1/platform-operators/auth/token-refreshes")
                        .cookie(activeRefresh)
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(false))
                .andReturn();
        Cookie rotatedRefresh = refreshed.getResponse().getCookie("MIRIYUM_PLATFORM_OPERATOR_REFRESH");

        MvcResult csrf = mvc.perform(get("/api/v1/platform-operators/auth/csrf-tokens/current"))
                .andExpect(status().isOk()).andReturn();
        String csrfToken = com.jayway.jsonpath.JsonPath.read(csrf.getResponse().getContentAsString(), "$.data.token");
        mvc.perform(delete("/api/v1/platform-operators/auth/sessions/current")
                        .cookie(rotatedRefresh, csrf.getResponse().getCookie("MIRIYUM_PLATFORM_OPERATOR_XSRF_TOKEN"))
                        .header("X-CSRF-TOKEN", csrfToken))
                .andExpect(status().isOk());

        assertThat(limitedRefresh).isNotNull();
        mvc.perform(post("/api/v1/platform-operators/auth/accounts"))
                .andExpect(status().isNotFound());
    }

    @RestController
    static class BusinessController {
        @GetMapping("/api/v1/platform-operators/test-business")
        String business() { return "ok"; }
    }
}
