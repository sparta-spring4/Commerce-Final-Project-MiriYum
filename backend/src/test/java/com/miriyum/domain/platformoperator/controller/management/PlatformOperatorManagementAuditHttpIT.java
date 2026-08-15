package com.miriyum.domain.platformoperator.controller.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.repository.AdminCaseAssignmentRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
class PlatformOperatorManagementAuditHttpIT {

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
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired AdminCaseAssignmentRepository assignments;
    @Autowired JdbcTemplate jdbc;

    @Test
    void superAdminCreatesAuditorAndAuditReadsRequireLeastPrivilegeAndAreAudited() throws Exception {
        PlatformOperatorAccount superAdmin = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "super@example.com", passwordEncoder.encode("Password1!"), "super",
                Instant.now().plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                superAdmin.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));
        assignments.saveAndFlush(AdminCaseAssignment.assign(
                AdminCaseType.OPERATOR_MANAGEMENT, "operator-management-1", 1L,
                superAdmin.getId(), Instant.now().plusSeconds(600), Instant.now()));

        String superToken = activate("super@example.com", "Password1!", "Changed2@");
        String provisioningId = UUID.randomUUID().toString();
        String creationApproval = approval(superToken, "OPERATOR_CREATION",
                "PLATFORM_OPERATOR_ACCOUNT", provisioningId, "Changed2@");

        MvcResult created = mvc.perform(post("/api/v1/platform-operators/accounts")
                        .header("Authorization", "Bearer " + superToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Admin-Reauthentication", creationApproval)
                        .header("X-Admin-Case-Id", "operator-management-1")
                        .header("X-Admin-Case-Version", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provisioningId":"%s","email":"auditor@example.com",
                                 "displayName":"auditor","temporaryPassword":"Auditor1!",
                                 "roles":["AUDIT_READER"],"directPermissions":[],
                                 "reason":"ACCOUNT_PROVISIONING"}
                                """.formatted(provisioningId)))
                .andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Auditor1!"))))
                .andExpect(jsonPath("$.data.roles[0]").value("AUDIT_READER"))
                .andReturn();
        long auditorId = Long.parseLong(JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.operatorId"));

        String auditorToken = activate("auditor@example.com", "Auditor1!", "Auditor2@");
        assignments.saveAndFlush(AdminCaseAssignment.assign(
                AdminCaseType.AUDIT_REVIEW, "audit-review-auditor", 1L,
                auditorId, Instant.now().plusSeconds(600), Instant.now()));

        MvcResult auditSearch = mvc.perform(get("/api/v1/platform-operators/audit-events")
                        .header("Authorization", "Bearer " + auditorToken)
                        .header("X-Admin-Case-Id", "audit-review-auditor")
                        .header("X-Admin-Case-Version", "1")
                        .header("X-Admin-Reason-Code", "AUDIT_VERIFICATION")
                        .queryParam("action", "ACCOUNT_CREATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].source").value("ADMIN"))
                .andExpect(jsonPath("$.data.content[0].action").value("ACCOUNT_CREATED"))
                .andExpect(jsonPath("$.data.content[0].roles",
                        org.hamcrest.Matchers.hasItem("SUPER_ADMIN")))
                .andExpect(jsonPath("$.data.content[0].afterRoles",
                        org.hamcrest.Matchers.hasItem("AUDIT_READER")))
                .andReturn();
        String originalEventKey = JsonPath.read(
                auditSearch.getResponse().getContentAsString(), "$.data.content[0].eventKey");

        assignments.saveAndFlush(AdminCaseAssignment.assign(
                AdminCaseType.AUDIT_REVIEW, "audit-review-super", 1L,
                superAdmin.getId(), Instant.now().plusSeconds(600), Instant.now()));
        String correctionApproval = approval(superToken, "AUDIT_CORRECTION",
                "AUDIT_EVENT", originalEventKey, "Changed2@");
        mvc.perform(post("/api/v1/platform-operators/audit-events/{eventKey}/corrections", originalEventKey)
                        .header("Authorization", "Bearer " + superToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Admin-Reauthentication", correctionApproval)
                        .header("X-Admin-Case-Id", "audit-review-super")
                        .header("X-Admin-Case-Version", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"RECORD_CORRECTION","correctedReason":"SECURITY_RESPONSE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originalEventKey").value(originalEventKey))
                .andExpect(jsonPath("$.data.action").value("AUDIT_CORRECTION"))
                .andExpect(jsonPath("$.data.correctedReason").value("SECURITY_RESPONSE"));

        String[] originalIdentity = originalEventKey.split(":", 2);
        assertThat(jdbc.queryForObject("""
                select count(*) from platform_operator_audit_events
                 where original_event_source = ? and original_event_id = ?
                   and action = 'AUDIT_CORRECTION' and reason = 'RECORD_CORRECTION'
                """, Long.class, originalIdentity[0], Long.parseLong(originalIdentity[1]))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from platform_operator_audit_events
                 where platform_operator_audit_event_id = ? and action = 'ACCOUNT_CREATED'
                """, Long.class, Long.parseLong(originalIdentity[1]))).isEqualTo(1L);

        mvc.perform(get("/api/v1/platform-operators/audit-events")
                        .header("Authorization", "Bearer " + superToken)
                        .header("X-Admin-Case-Id", "audit-review-super")
                        .header("X-Admin-Case-Version", "1")
                        .header("X-Admin-Reason-Code", "AUDIT_VERIFICATION"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_001"));

        assertThat(jdbc.queryForObject("""
                select count(*) from platform_operator_audit_events
                 where action = 'AUDIT_SEARCH' and outcome = 'DENIED'
                   and actor_platform_operator_account_id = ?
                """, Long.class, superAdmin.getId())).isEqualTo(1L);
    }

    private String activate(String email, String temporaryPassword, String activePassword) throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/platform-operators/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + temporaryPassword + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String access = JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");
        MvcResult changed = mvc.perform(put("/api/v1/platform-operators/auth/initial-password")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"%s","newPasswordConfirm":"%s"}
                                """.formatted(temporaryPassword, activePassword, activePassword)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(changed.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private String approval(
            String accessToken,
            String purpose,
            String targetType,
            String targetId,
            String password
    ) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/platform-operators/reauthentication-approvals")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","purpose":"%s","targetType":"%s","targetId":"%s"}
                                """.formatted(password, purpose, targetType, targetId)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.approval");
    }
}
