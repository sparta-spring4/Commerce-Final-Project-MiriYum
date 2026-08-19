package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus;
import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryApproval;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryExecution;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryApprovalRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryExecutionRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryProposalRepository;
import com.miriyum.domain.platformoperator.repository.AdminCaseAssignmentRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-d")
@Testcontainers
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.platform-operator.enabled=true",
        "miriyum.platform-operator.reauthentication-fingerprint-secret=test-only-reauthentication-fingerprint-secret",
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3",
        "miriyum.platform-operator.payment-recovery-initial-delay-ms=3600000",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
class PaymentRecoveryExecutionRuntimeIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PaymentRecoveryExecutionTransaction transactions;
    @Autowired PaymentRecoveryCaseRepository cases;
    @Autowired PaymentRecoveryProposalRepository proposals;
    @Autowired PaymentRecoveryApprovalRepository approvals;
    @Autowired PaymentRecoveryExecutionRepository executions;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired AdminCaseAssignmentRepository assignments;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void twoWorkersCanClaimOneExecutionOnlyOnce() throws Exception {
        Instant now = Instant.now();
        PlatformOperatorAccount operator = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "recovery-worker@example.com", encoder.encode("Password1!"), "recovery-worker",
                now.plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR, now));
        PaymentRecoveryCase recoveryCase = PaymentRecoveryCase.open(
                "281", RecoveryKind.REFUND_FAILED, ResultStatus.FAILED,
                300_000L, 0L, 300_000L, "KRW", Set.of(RecoveryAction.RETRY_REFUND),
                "port********abc", 3L, 4L, 5L, now);
        recoveryCase.beginInvestigation(1L, now);
        cases.saveAndFlush(recoveryCase);
        PaymentRecoveryProposal proposal = PaymentRecoveryProposal.propose(
                recoveryCase.getPublicId(), 1L, 2L, RecoveryAction.RETRY_REFUND,
                100_000L, 100_000L, 300_000L, "KRW", 3L, 4L, 5L,
                "a".repeat(64), operator.getId(), 1L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                UUID.randomUUID().toString(), now);
        proposals.saveAndFlush(proposal);
        recoveryCase.recordProposal(2L, 1L, proposal.getApprovalTier(), now);
        PaymentRecoveryApproval approval = approvals.saveAndFlush(PaymentRecoveryApproval.approve(
                proposal, operator.getId(), 1L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                UUID.randomUUID().toString(), now));
        recoveryCase.queueExecution(3L, now);
        cases.saveAndFlush(recoveryCase);
        executions.saveAndFlush(PaymentRecoveryExecution.authorize(proposal, approval, now));
        assignments.saveAndFlush(AdminCaseAssignment.assign(AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), recoveryCase.getCaseVersion(), operator.getId(),
                now.plusSeconds(600), now));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Optional<PaymentRecoveryExecutionTransaction.Claim>> outcomes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> claim("worker-a", ready, start));
            var second = pool.submit(() -> claim("worker-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(outcomes.stream().filter(Optional::isPresent)).hasSize(1);
    }

    @Test
    void expiredRequeryLeaseIsReclaimedAsRequeryRatherThanRefundRequest() {
        Instant now = Instant.now();
        Instant originalAttempt = now.minusSeconds(120);
        PlatformOperatorAccount operator = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "requery-reclaim@example.com", encoder.encode("Password1!"), "requery-reclaim",
                now.plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR, now));
        PaymentRecoveryCase recoveryCase = PaymentRecoveryCase.open(
                "28102", RecoveryKind.REFUND_RESULT_UNKNOWN, ResultStatus.UNKNOWN,
                300_000L, 0L, 300_000L, "KRW", Set.of(RecoveryAction.REQUERY_PROVIDER_RESULT),
                "port********002", 3L, 4L, 5L, originalAttempt);
        recoveryCase.beginInvestigation(1L, originalAttempt);
        cases.saveAndFlush(recoveryCase);
        PaymentRecoveryExecution execution = PaymentRecoveryExecution.authorizeRequery(
                recoveryCase, operator.getId(), 1L, originalAttempt);
        recoveryCase.queueRequery(2L, originalAttempt);
        cases.saveAndFlush(recoveryCase);
        assignments.saveAndFlush(AdminCaseAssignment.assign(AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), recoveryCase.getCaseVersion(), operator.getId(),
                now.plusSeconds(600), now));
        execution.claim("crashed-query-worker", originalAttempt,
                originalAttempt.plusSeconds(30));
        executions.saveAndFlush(execution);

        var reclaimed = transactions.claim("replacement-worker").orElseThrow();

        assertThat(reclaimed.operation()).isEqualTo(RecoveryAction.REQUERY_PROVIDER_RESULT);
        assertThat(reclaimed.operationId()).isEqualTo(execution.getOperationId());

        transactions.recordInspection(reclaimed, new ManualRecoveryInspection(
                recoveryCase.getHandoffId(), 4L, 5L, 6L,
                ManualRecoveryKind.REFUND_RESULT_UNKNOWN, 300_000L, 300_000L, 0L,
                "KRW", ManualRecoveryResultStatus.SUCCEEDED, Set.of(), "port********002"));

        PaymentRecoveryCase completed = cases.findByPublicId(
                recoveryCase.getPublicId()).orElseThrow();
        assertThat(completed.getResultStatus()).isEqualTo(ResultStatus.SUCCEEDED);
        assertThat(completed.getCumulativeRefundedAmountMinor()).isEqualTo(300_000L);
        assertThat(completed.getRemainingRefundableAmountMinor()).isZero();
        assertThat(completed.getAllowedActions()).isEmpty();
        assertThat(completed.getHandoffVersion()).isEqualTo(4L);
        assertThat(completed.getPaymentVersion()).isEqualTo(5L);
        assertThat(completed.getRecoveryVersion()).isEqualTo(6L);
        assertThat(jdbc.queryForObject("""
                select platform_operator_account_id from admin_case_assignments
                where case_type = 'PAYMENT_RECOVERY' and case_id = ? and case_version = ?
                """, Long.class, completed.getPublicId(), completed.getCaseVersion()))
                .isEqualTo(operator.getId());

        assertThat(jdbc.queryForObject("""
                select count(*) from platform_operator_audit_events
                where action = 'PAYMENT_RECOVERY_VERIFIED' and target_id = ?
                """, Long.class, execution.getExecutionKey())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select json_unquote(json_extract(after_snapshot, '$.resultStatus'))
                from platform_operator_audit_events
                where action = 'PAYMENT_RECOVERY_VERIFIED' and target_id = ?
                """, String.class, execution.getExecutionKey())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                select cast(json_unquote(json_extract(after_snapshot, '$.paymentVersion')) as unsigned)
                from platform_operator_audit_events
                where action = 'PAYMENT_RECOVERY_VERIFIED' and target_id = ?
                """, Long.class, execution.getExecutionKey())).isEqualTo(5L);
    }

    @Test
    void currentVersionReassignmentRejectsRequesterDespiteActiveAuthorizedVersionAssignment() {
        Instant now = Instant.now();
        PlatformOperatorAccount requester = operator("current-reassigned-requester@example.com", now);
        PlatformOperatorAccount replacement = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "current-reassigned-owner@example.com", encoder.encode("Password1!"),
                "current-reassigned-owner", now.plusSeconds(600)));
        ExecutionFixture fixture = refundExecution("28103", requester, now);
        assignments.saveAndFlush(AdminCaseAssignment.assign(AdminCaseType.PAYMENT_RECOVERY,
                fixture.recoveryCase().getPublicId(), fixture.execution().getAuthorizedCaseVersion(),
                requester.getId(), now.plusSeconds(600), now));
        assignments.saveAndFlush(AdminCaseAssignment.assign(AdminCaseType.PAYMENT_RECOVERY,
                fixture.recoveryCase().getPublicId(), fixture.recoveryCase().getCaseVersion(),
                replacement.getId(), now.plusSeconds(600), now));

        assertThat(transactions.claim("reassigned-worker")).isEmpty();

        assertThat(executions.findById(fixture.execution().getId()).orElseThrow().getStatus())
                .isEqualTo(com.miriyum.domain.platformoperator.paymentrecovery.entity
                        .PaymentRecoveryEnums.ExecutionStatus.HOLD);
    }

    @Test
    void currentVersionAssignmentAllowsClaimAfterAuthorizedVersionAssignmentExpires() {
        Instant now = Instant.now();
        PlatformOperatorAccount requester = operator("current-valid-requester@example.com", now);
        ExecutionFixture fixture = refundExecution("28104", requester, now);
        assignments.saveAndFlush(AdminCaseAssignment.assign(AdminCaseType.PAYMENT_RECOVERY,
                fixture.recoveryCase().getPublicId(), fixture.execution().getAuthorizedCaseVersion(),
                requester.getId(), now.minusSeconds(60), now.minusSeconds(120)));
        assignments.saveAndFlush(AdminCaseAssignment.assign(AdminCaseType.PAYMENT_RECOVERY,
                fixture.recoveryCase().getPublicId(), fixture.recoveryCase().getCaseVersion(),
                requester.getId(), now.plusSeconds(600), now));

        var claim = transactions.claim("current-assignment-worker").orElseThrow();

        assertThat(claim.executionId()).isEqualTo(fixture.execution().getId());
    }

    private PlatformOperatorAccount operator(String email, Instant now) {
        PlatformOperatorAccount operator = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                email, encoder.encode("Password1!"), email.substring(0, email.indexOf('@')),
                now.plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                operator.getId(), PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR, now));
        return operator;
    }

    private ExecutionFixture refundExecution(
            String handoffId, PlatformOperatorAccount requester, Instant now) {
        PaymentRecoveryCase recoveryCase = PaymentRecoveryCase.open(
                handoffId, RecoveryKind.REFUND_FAILED, ResultStatus.FAILED,
                300_000L, 0L, 300_000L, "KRW", Set.of(RecoveryAction.RETRY_REFUND),
                "port********" + handoffId.substring(handoffId.length() - 3),
                3L, 4L, 5L, now);
        recoveryCase.beginInvestigation(1L, now);
        cases.saveAndFlush(recoveryCase);
        PaymentRecoveryProposal proposal = proposals.saveAndFlush(PaymentRecoveryProposal.propose(
                recoveryCase.getPublicId(), 1L, 2L, RecoveryAction.RETRY_REFUND,
                100_000L, 100_000L, 300_000L, "KRW", 3L, 4L, 5L,
                "a".repeat(64), requester.getId(), 1L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                UUID.randomUUID().toString(), now));
        recoveryCase.recordProposal(2L, 1L, proposal.getApprovalTier(), now);
        PaymentRecoveryApproval approval = approvals.saveAndFlush(PaymentRecoveryApproval.approve(
                proposal, requester.getId(), 1L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                UUID.randomUUID().toString(), now));
        recoveryCase.queueExecution(3L, now);
        cases.saveAndFlush(recoveryCase);
        PaymentRecoveryExecution execution = executions.saveAndFlush(
                PaymentRecoveryExecution.authorize(proposal, approval, now));
        return new ExecutionFixture(recoveryCase, execution);
    }

    private record ExecutionFixture(
            PaymentRecoveryCase recoveryCase, PaymentRecoveryExecution execution) {
    }

    private Optional<PaymentRecoveryExecutionTransaction.Claim> claim(
            String owner, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("timeout");
        try {
            return transactions.claim(owner);
        } catch (RuntimeException contention) {
            return Optional.empty();
        }
    }
}
