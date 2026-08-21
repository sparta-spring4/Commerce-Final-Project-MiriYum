package com.miriyum.domain.platformoperator.paymentrecovery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRefundPreview;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryProposalRepository;
import com.miriyum.domain.platformoperator.repository.AdminCaseAssignmentRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
        "miriyum.rate-limit.login.max-requests=100",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
@AutoConfigureMockMvc
class PaymentRecoveryHttpIT {
    private static final Path CONTRACT = Path.of(
            "..", "docs", "specs", "payment-recovery", "openapi.yaml");
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
    @Autowired PaymentRecoveryProposalRepository proposals;
    @Autowired AdminCaseAssignmentRepository assignments;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentService payments;

    @Test
    void differentSuperAdminCanListAndOpenPendingAdditionalApproval() throws Exception {
        PlatformOperatorAccount requester = createOperator("payment-recovery-requester@example.com");
        PlatformOperatorAccount approver = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "payment-recovery-approver@example.com", encoder.encode("Password1!"),
                "payment-recovery-approver", Instant.now().plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                approver.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                approver.getId(), PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR, Instant.now()));

        Instant now = Instant.now();
        PaymentRecoveryCase recoveryCase = PaymentRecoveryCase.open(
                "991284", RecoveryKind.REFUND_FAILED, ResultStatus.FAILED,
                400_000L, 100_000L, 300_000L, "KRW",
                Set.of(RecoveryAction.RETRY_REFUND), "port********approval",
                3L, 4L, 5L, now);
        recoveryCase.beginInvestigation(1L, now);
        recoveryCase = cases.saveAndFlush(recoveryCase);
        proposals.saveAndFlush(PaymentRecoveryProposal.propose(
                recoveryCase.getPublicId(), 1L, 2L, RecoveryAction.RETRY_REFUND,
                250_000L, 250_000L, 400_000L, "KRW", 3L, 4L, 5L,
                "a".repeat(64), requester.getId(), 1L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                UUID.randomUUID().toString(), now));
        recoveryCase.recordProposal(2L, 1L, ApprovalTier.ADDITIONAL_SUPER_ADMIN, now.plusSeconds(1));
        cases.saveAndFlush(recoveryCase);
        assignments.saveAndFlush(AdminCaseAssignment.assign(
                com.miriyum.domain.platformoperator.enums.AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), recoveryCase.getCaseVersion(), requester.getId(),
                now.plusSeconds(600), now));

        String token = loginAndActivate("payment-recovery-approver@example.com");
        assertThat(publicSummary(token, recoveryCase.getPublicId())
                .get("assignedToCurrentOperator")).isEqualTo(false);
        mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases/pending-additional-approvals")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].caseId").value(recoveryCase.getPublicId()))
                .andExpect(jsonPath("$.data.content[0].proposals[0].proposalVersion").value(1));
        mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases/{caseId}",
                        recoveryCase.getPublicId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseVersion").value(3));
    }

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
        mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].assignedOperatorId").value(operator.getId()))
                .andExpect(jsonPath("$.data.content[0].assignedToCurrentOperator").value(true));
        Long auditId = jdbc.queryForObject("""
                select platform_operator_audit_event_id from platform_operator_audit_events
                where action = 'PAYMENT_RECOVERY_CASE_ASSIGNED' and case_id = ?
                """, Long.class, recoveryCase.getPublicId());
        assertThatThrownBy(() -> jdbc.update("""
                update platform_operator_audit_events set target_id = 'tampered'
                where platform_operator_audit_event_id = ?
                """, auditId)).isInstanceOf(DataAccessException.class);
        String detailResponse = mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases/{caseId}",
                        recoveryCase.getPublicId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseId").value(recoveryCase.getPublicId()))
                .andExpect(content().string(not(containsString("Changed2@"))))
                .andExpect(content().string(not(containsString(approval))))
                .andReturn().getResponse().getContentAsString();
        assertMatchesLocalSchema(JsonPath.read(detailResponse, "$"), "CaseDetailEnvelope");

        mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases")
                        .header("Authorization", "Bearer "
                                + jwt.generateAccessToken(TokenNamespace.CONSUMER, 1L)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicResponseVersionsDriveRequeryAndProposalCommands() throws Exception {
        createOperator("payment-recovery-versions@example.com");
        String token = loginAndActivate("payment-recovery-versions@example.com");
        PaymentRecoveryCase requeryCase = cases.saveAndFlush(PaymentRecoveryCase.open(
                "282", RecoveryKind.REFUND_RESULT_UNKNOWN, ResultStatus.UNKNOWN,
                300_000L, 100_000L, 200_000L, "KRW",
                Set.of(RecoveryAction.REQUERY_PROVIDER_RESULT), "port********rq1",
                13L, 14L, 15L, Instant.now()));
        PaymentRecoveryCase proposalCase = cases.saveAndFlush(PaymentRecoveryCase.open(
                "283", RecoveryKind.REFUND_FAILED, ResultStatus.FAILED,
                300_000L, 100_000L, 200_000L, "KRW",
                Set.of(RecoveryAction.RETRY_REFUND), "port********pr1",
                23L, 24L, 25L, Instant.now()));

        Map<String, Object> requerySummary = publicSummary(token, requeryCase.getPublicId());
        String assignedRequery = assign(token, requeryCase.getPublicId(),
                number(requerySummary, "caseVersion"));
        long assignedRequeryVersion = jsonNumber(assignedRequery, "$.data.caseVersion");
        long handoffVersion = number(requerySummary, "handoffVersion");
        long paymentVersion = number(requerySummary, "paymentVersion");
        long recoveryVersion = number(requerySummary, "recoveryVersion");
        String requeryBody = "{\"expectedCaseVersion\":" + assignedRequeryVersion
                + ",\"expectedHandoffVersion\":" + handoffVersion
                + ",\"expectedPaymentVersion\":" + paymentVersion
                + ",\"expectedRecoveryVersion\":" + recoveryVersion + "}";
        mvc.perform(post("/api/v1/platform-operators/payment-recovery-cases/{caseId}/requeries",
                        requeryCase.getPublicId()).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Admin-Reauthentication", reauthenticate(token, requeryCase.getPublicId()))
                        .header("X-Correlation-Id", "corr-http-requery-versions")
                        .contentType(MediaType.APPLICATION_JSON).content(requeryBody))
                .andExpect(status().isAccepted());

        Map<String, Object> proposalSummary = publicSummary(token, proposalCase.getPublicId());
        String assignedProposal = assign(token, proposalCase.getPublicId(),
                number(proposalSummary, "caseVersion"));
        long proposalCaseVersion = jsonNumber(assignedProposal, "$.data.caseVersion");
        long proposalHandoffVersion = number(proposalSummary, "handoffVersion");
        long proposalPaymentVersion = number(proposalSummary, "paymentVersion");
        long proposalRecoveryVersion = number(proposalSummary, "recoveryVersion");
        when(payments.previewManualRecoveryRefund(any())).thenReturn(new ManualRecoveryRefundPreview(
                "283", 23L, 24L, 25L, 300_000L, 100_000L, 50_000L,
                200_000L, "KRW", true));
        String proposalBody = "{\"action\":\"RETRY_REFUND\",\"expectedCaseVersion\":"
                + proposalCaseVersion + ",\"expectedHandoffVersion\":" + proposalHandoffVersion
                + ",\"expectedPaymentVersion\":" + proposalPaymentVersion
                + ",\"expectedRecoveryVersion\":" + proposalRecoveryVersion + "}";
        mvc.perform(post("/api/v1/platform-operators/payment-recovery-cases/{caseId}/proposals",
                        proposalCase.getPublicId()).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Admin-Reauthentication", reauthenticate(token, proposalCase.getPublicId()))
                        .header("X-Correlation-Id", "corr-http-proposal-versions")
                        .contentType(MediaType.APPLICATION_JSON).content(proposalBody))
                .andExpect(status().isCreated());
    }

    @Test
    void heldCaseResumesAndExpiredInvestigatingAssignmentTransfersWithoutStealingActiveWork() throws Exception {
        PlatformOperatorAccount resumeOperator = createOperator("payment-recovery-resume@example.com");
        String resumeToken = loginAndActivate("payment-recovery-resume@example.com");
        PaymentRecoveryCase held = PaymentRecoveryCase.open(
                "284", RecoveryKind.REFUND_RESULT_UNKNOWN, ResultStatus.UNKNOWN,
                300_000L, 100_000L, 200_000L, "KRW",
                Set.of(RecoveryAction.REQUERY_PROVIDER_RESULT), "port********hold",
                33L, 34L, 35L, Instant.now());
        held.beginInvestigation(1L, Instant.now());
        held.queueRequery(2L, Instant.now());
        held.startVerification(3L, Instant.now());
        held.hold(4L, Instant.now());
        cases.saveAndFlush(held);

        String resumed = assign(resumeToken, held.getPublicId(), 5L);
        assertThat((String) JsonPath.read(resumed, "$.data.status")).isEqualTo("INVESTIGATING");
        assertThat((Integer) JsonPath.read(resumed, "$.data.caseVersion")).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                select platform_operator_account_id from admin_case_assignments
                where case_type = 'PAYMENT_RECOVERY' and case_id = ? and case_version = 6
                """, Long.class, held.getPublicId())).isEqualTo(resumeOperator.getId());

        PlatformOperatorAccount former = createOperator("payment-recovery-former@example.com");
        PlatformOperatorAccount next = createOperator("payment-recovery-next@example.com");
        createOperator("payment-recovery-contender@example.com");
        PaymentRecoveryCase investigating = PaymentRecoveryCase.open(
                "285", RecoveryKind.REFUND_FAILED, ResultStatus.FAILED,
                300_000L, 100_000L, 200_000L, "KRW",
                Set.of(RecoveryAction.RETRY_REFUND), "port********renew",
                43L, 44L, 45L, Instant.now());
        investigating.beginInvestigation(1L, Instant.now());
        cases.saveAndFlush(investigating);
        assignments.saveAndFlush(AdminCaseAssignment.assign(
                com.miriyum.domain.platformoperator.enums.AdminCaseType.PAYMENT_RECOVERY,
                investigating.getPublicId(), 2L, former.getId(),
                Instant.now().minusSeconds(30), Instant.now().minusSeconds(90)));

        String nextToken = loginAndActivate("payment-recovery-next@example.com");
        String renewed = assign(nextToken, investigating.getPublicId(), 2L);
        assertThat((Integer) JsonPath.read(renewed, "$.data.caseVersion")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select platform_operator_account_id from admin_case_assignments
                where case_type = 'PAYMENT_RECOVERY' and case_id = ? and case_version = 2
                """, Long.class, investigating.getPublicId())).isEqualTo(next.getId());

        String contenderToken = loginAndActivate("payment-recovery-contender@example.com");
        mvc.perform(post("/api/v1/platform-operators/payment-recovery-cases/{caseId}/assignments",
                        investigating.getPublicId()).header("Authorization", "Bearer " + contenderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Admin-Reauthentication",
                                reauthenticate(contenderToken, investigating.getPublicId()))
                        .header("X-Correlation-Id", "corr-http-active-takeover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCaseVersion\":2}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("""
                select platform_operator_account_id from admin_case_assignments
                where case_type = 'PAYMENT_RECOVERY' and case_id = ? and case_version = 2
                """, Long.class, investigating.getPublicId())).isEqualTo(next.getId());
    }

    private PlatformOperatorAccount createOperator(String email) {
        PlatformOperatorAccount operator = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                email, encoder.encode("Password1!"), email.substring(0, email.indexOf('@')),
                Instant.now().plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR, Instant.now()));
        return operator;
    }

    private String loginAndActivate(String email) throws Exception {
        String limited = JsonPath.read(mvc.perform(post("/api/v1/platform-operators/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password1!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.accessToken");
        return JsonPath.read(mvc.perform(put("/api/v1/platform-operators/auth/initial-password")
                        .header("Authorization", "Bearer " + limited)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Password1!\",\"newPassword\":\"Changed2@\","
                                + "\"newPasswordConfirm\":\"Changed2@\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.accessToken");
    }

    private String reauthenticate(String token, String caseId) throws Exception {
        String body = "{\"currentPassword\":\"Changed2@\",\"purpose\":\"PAYMENT_RECOVERY\","
                + "\"targetType\":\"PAYMENT_RECOVERY_CASE\",\"targetId\":\"" + caseId + "\"}";
        return JsonPath.read(mvc.perform(post("/api/v1/platform-operators/reauthentication-approvals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.approval");
    }

    private String assign(String token, String caseId, long expectedCaseVersion) throws Exception {
        return mvc.perform(post("/api/v1/platform-operators/payment-recovery-cases/{caseId}/assignments",
                        caseId).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Admin-Reauthentication", reauthenticate(token, caseId))
                        .header("X-Correlation-Id", "corr-http-assign-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCaseVersion\":" + expectedCaseVersion + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publicSummary(String token, String caseId) throws Exception {
        String body = mvc.perform(get("/api/v1/platform-operators/payment-recovery-cases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> content = JsonPath.read(body, "$.data.content");
        return content.stream().filter(item -> caseId.equals(item.get("caseId"))).findFirst()
                .orElseThrow();
    }

    private static long number(Map<String, Object> value, String key) {
        return ((Number) value.get(key)).longValue();
    }

    private static long jsonNumber(String body, String path) {
        return ((Number) JsonPath.read(body, path)).longValue();
    }

    private static void assertMatchesLocalSchema(Object value, String schemaName) throws Exception {
        try (InputStream input = Files.newInputStream(CONTRACT)) {
            Map<String, Object> document = map(new Yaml().load(input));
            Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
            assertMatchesSchema(value, map(schemas.get(schemaName)), schemas);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertMatchesSchema(
            Object value, Map<String, Object> schema, Map<String, Object> schemas) {
        Object reference = schema.get("$ref");
        if (reference instanceof String ref) {
            if (ref.startsWith("#/components/schemas/")) {
                assertMatchesSchema(value,
                        map(schemas.get(ref.substring("#/components/schemas/".length()))), schemas);
            }
            return;
        }
        Object allOf = schema.get("allOf");
        if (allOf instanceof List<?> branches) {
            branches.forEach(branch -> assertMatchesSchema(value, map(branch), schemas));
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> properties = schema.containsKey("properties")
                    ? map(schema.get("properties")) : Map.of();
            List<String> required = schema.containsKey("required")
                    ? (List<String>) schema.get("required") : List.of();
            Set<String> actualKeys = object.keySet().stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.toSet());
            assertThat(actualKeys).containsAll(required);
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                assertThat(actualKeys).isSubsetOf(properties.keySet());
            }
            properties.forEach((name, propertySchema) -> {
                if (object.containsKey(name) && object.get(name) != null) {
                    assertMatchesSchema(object.get(name), map(propertySchema), schemas);
                }
            });
        } else if (value instanceof List<?> values && schema.containsKey("items")) {
            values.forEach(item -> assertMatchesSchema(item, map(schema.get("items")), schemas));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
