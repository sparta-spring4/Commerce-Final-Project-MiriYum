package com.miriyum.domain.platformoperator.paymentrecovery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-d")
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
class PaymentRecoveryHttpIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");
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
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired PaymentRecoveryCaseRepository cases;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void authenticatedSelfAssignmentIsReauthenticatedIdempotentAndMasked() throws Exception {
        PlatformOperatorAccount operator = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "payment-recovery-http@example.com", encoder.encode("Password1!"),
                "payment-recovery-http", Instant.now().plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR, Instant.now()));
        PaymentRecoveryCase recoveryCase = cases.saveAndFlush(PaymentRecoveryCase.open(
                "281", RecoveryKind.REFUND_RESULT_UNKNOWN, ResultStatus.UNKNOWN,
                300_000L, 100_000L, 200_000L, "KRW",
                Set.of(RecoveryAction.REQUERY_PROVIDER_RESULT), "port********abc",
                3L, 4L, 5L, Instant.now()));
        String limited = JsonPath.read(mvc.perform(post("/api/v1/platform-operators/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"payment-recovery-http@example.com\",\"password\":\"Password1!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.accessToken");
        String token = JsonPath.read(mvc.perform(put("/api/v1/platform-operators/auth/initial-password")
                        .header("Authorization", "Bearer " + limited)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Password1!\",\"newPassword\":\"Changed2@\","
                                + "\"newPasswordConfirm\":\"Changed2@\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.accessToken");

        mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].caseId").value(recoveryCase.getPublicId()))
                .andExpect(content().string(not(containsString("portOnePaymentId"))))
                .andExpect(content().string(not(containsString("providerPayload"))))
                .andExpect(content().string(not(containsString("card"))));
        mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases/{caseId}",
                        recoveryCase.getPublicId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        String reauthBody = "{\"currentPassword\":\"Changed2@\",\"purpose\":\"PAYMENT_RECOVERY\","
                + "\"targetType\":\"PAYMENT_RECOVERY_CASE\",\"targetId\":\""
                + recoveryCase.getPublicId() + "\"}";
        String approval = JsonPath.read(mvc.perform(post(
                        "/api/v1/platform-operators/reauthentication-approvals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(reauthBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.approval");
        String key = UUID.randomUUID().toString();
        String assignmentBody = "{\"expectedCaseVersion\":1}";
        mvc.perform(post("/api/v1/platform-operators/payment-recovery-cases/{caseId}/assignments",
                        recoveryCase.getPublicId()).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .header("X-Admin-Reauthentication", approval)
                        .header("X-Correlation-Id", "corr-http-281")
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.data.caseVersion").value(2));
        mvc.perform(post("/api/v1/platform-operators/payment-recovery-cases/{caseId}/assignments",
                        recoveryCase.getPublicId()).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .header("X-Admin-Reauthentication", "already-consumed-is-not-read-on-replay")
                        .header("X-Correlation-Id", "corr-http-281")
                        .contentType(MediaType.APPLICATION_JSON).content(assignmentBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseVersion").value(2));
        assertThat(jdbc.queryForObject("""
                select platform_operator_account_id from admin_case_assignments
                where case_type = 'PAYMENT_RECOVERY' and case_id = ? and case_version = 2
                """, Long.class, recoveryCase.getPublicId())).isEqualTo(operator.getId());
        assertThat(jdbc.queryForObject("select case_version from payment_recovery_cases where case_public_id = ?",
                Long.class, recoveryCase.getPublicId())).isEqualTo(2L);
        Long auditId = jdbc.queryForObject("""
                select platform_operator_audit_event_id from platform_operator_audit_events
                where action = 'PAYMENT_RECOVERY_CASE_ASSIGNED' and case_id = ?
                """, Long.class, recoveryCase.getPublicId());
        assertThatThrownBy(() -> jdbc.update("""
                update platform_operator_audit_events set target_id = 'tampered'
                where platform_operator_audit_event_id = ?
                """, auditId)).isInstanceOf(DataAccessException.class);
        mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases/{caseId}",
                        recoveryCase.getPublicId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseId").value(recoveryCase.getPublicId()))
                .andExpect(content().string(not(containsString("Changed2@"))))
                .andExpect(content().string(not(containsString(approval))));

        mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases")
                        .header("Authorization", "Bearer "
                                + jwt.generateAccessToken(TokenNamespace.CONSUMER, 1L)))
                .andExpect(status().isUnauthorized());
    }
}
