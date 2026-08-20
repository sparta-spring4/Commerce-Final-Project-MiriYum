package com.miriyum.domain.platformoperator.controller.management;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.SessionTokenClaims;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorPermissionGrant;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.platform-operator.enabled=true",
        "miriyum.platform-operator.reauthentication-fingerprint-secret=test-only-reauthentication-fingerprint-secret",
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
@AutoConfigureMockMvc
class PlatformOperatorAccountQueryHttpIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");
    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired PlatformOperatorPermissionGrantRepository permissions;
    @Autowired OperatorAuthorityService authorityService;
    @Autowired JwtTokenProvider jwtTokenProvider;
    private int loginRequestSequence;

    @Test
    void centralSessionAndManagePermissionProtectSelfListAndDetailReads() throws Exception {
        PlatformOperatorAccount superAdmin = create("super-query@example.com", "Super Query");
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                superAdmin.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));

        MvcResult limitedLogin = login("super-query@example.com", "Password1!");
        String limitedToken = JsonPath.read(limitedLogin.getResponse().getContentAsString(), "$.data.accessToken");
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + limitedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_012"));

        String superToken = changePassword(limitedToken, "Password1!", "Changed2@");
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(3)))
                .andExpect(jsonPath("$.data.roles[0]").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.permissions",
                        org.hamcrest.Matchers.hasItem("OPERATOR_AUTHORITY_MANAGE")))
                .andExpect(jsonPath("$.data.permissions",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("AUDIT_READ"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("accessToken"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sessionId"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("passwordHash"))));

        PlatformOperatorAccount auditor = create("auditor-query@example.com", "Auditor Query");
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                auditor.getId(), PlatformOperatorRole.AUDIT_READER, Instant.now()));
        String auditorLimited = JsonPath.read(login("auditor-query@example.com", "Password1!")
                .getResponse().getContentAsString(), "$.data.accessToken");
        String auditorToken = changePassword(auditorLimited, "Password1!", "Auditor2@");

        mvc.perform(get("/api/v1/platform-operators/accounts/999999")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_001"));

        mvc.perform(get("/api/v1/platform-operators/accounts")
                        .header("Authorization", "Bearer " + superToken)
                        .queryParam("role", "AUDIT_READER")
                        .queryParam("query", "auditor-query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].operatorId").value(String.valueOf(auditor.getId())))
                .andExpect(jsonPath("$.data.content[0].email").value("au***********@e******.com"))
                .andExpect(jsonPath("$.data.content[0].lastLoginAt").isNotEmpty());

        mvc.perform(get("/api/v1/platform-operators/accounts/{operatorId}", auditor.getId())
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.directPermissions").isEmpty())
                .andExpect(jsonPath("$.data.effectivePermissions[0]").value("AUDIT_READ"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Auditor2@"))));

        mvc.perform(get("/api/v1/platform-operators/accounts/999999")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADMIN_008"));

        String replacementToken = JsonPath.read(login("super-query@example.com", "Changed2@")
                .getResponse().getContentAsString(), "$.data.accessToken");
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isUnauthorized());

        PlatformOperatorAccount stale = accounts.findById(superAdmin.getId()).orElseThrow();
        stale.advanceAuthorityVersion();
        accounts.saveAndFlush(stale);
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + replacementToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_015"));

        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer invalid-or-other-namespace-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer "
                                + jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, 1L)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer "
                                + jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, 1L)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void capabilitiesCombineDirectGrantAndReflectItsRevocation() throws Exception {
        PlatformOperatorAccount operator = create("capability-union@example.com", "Capability Union");
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.ONBOARDING_REVIEWER, Instant.now()));
        permissions.saveAndFlush(PlatformOperatorPermissionGrant.create(
                operator.getId(), PlatformOperatorPermission.AUDIT_READ, Instant.now()));
        String limited = JsonPath.read(login("capability-union@example.com", "Password1!")
                .getResponse().getContentAsString(), "$.data.accessToken");
        String token = changePassword(limited, "Password1!", "Changed2@");

        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("ONBOARDING_REVIEWER"))
                .andExpect(jsonPath("$.data.permissions[0]").value("AUDIT_READ"))
                .andExpect(jsonPath("$.data.permissions[1]").value("ONBOARDING_EVIDENCE_READ"))
                .andExpect(jsonPath("$.data.permissions[2]").value("ONBOARDING_REVIEW"));

        authorityService.revokePermission(operator.getId(), PlatformOperatorPermission.AUDIT_READ);
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_015"));

        String refreshedAuthorityToken = JsonPath.read(login("capability-union@example.com", "Changed2@")
                .getResponse().getContentAsString(), "$.data.accessToken");
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + refreshedAuthorityToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("AUDIT_READ"))));
    }

    @Test
    void unauthenticatedExpiredAndSuspendedOperatorsKeepExistingAuthenticationErrors() throws Exception {
        mvc.perform(get("/api/v1/platform-operators/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        JwtTokenProvider expiredTokenProvider = new JwtTokenProvider(
                "test-only-secret-key-must-be-at-least-32-bytes",
                "miriyum",
                Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
        String expiredToken = expiredTokenProvider.generateAccessToken(
                TokenNamespace.PLATFORM_OPERATOR,
                1L,
                new SessionTokenClaims("expired-session", 1L, 1L, false));
        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_002"));

        PlatformOperatorAccount operator = create("capability-suspended@example.com", "Capability Suspended");
        String limited = JsonPath.read(login("capability-suspended@example.com", "Password1!")
                .getResponse().getContentAsString(), "$.data.accessToken");
        String token = changePassword(limited, "Password1!", "Changed2@");
        PlatformOperatorAccount current = accounts.findById(operator.getId()).orElseThrow();
        current.suspend();
        accounts.saveAndFlush(current);

        mvc.perform(get("/api/v1/platform-operators/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_011"));
    }

    private PlatformOperatorAccount create(String email, String displayName) {
        return accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                email, encoder.encode("Password1!"), displayName, Instant.now().plusSeconds(600)));
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/platform-operators/auth/sessions")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100." + ++loginRequestSequence);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    private String changePassword(String token, String current, String next) throws Exception {
        MvcResult changed = mvc.perform(put("/api/v1/platform-operators/auth/initial-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next
                                + "\",\"newPasswordConfirm\":\"" + next + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(changed.getResponse().getContentAsString(), "$.data.accessToken");
    }
}
