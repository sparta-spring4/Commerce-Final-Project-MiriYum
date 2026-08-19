package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
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
                recoveryCase.getPublicId(), proposal.getExpectedCaseVersion(), operator.getId(),
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
