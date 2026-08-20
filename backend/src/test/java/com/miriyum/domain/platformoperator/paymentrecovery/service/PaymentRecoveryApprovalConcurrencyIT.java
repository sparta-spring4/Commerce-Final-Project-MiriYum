package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryApproval;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryApprovalRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryProposalRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class PaymentRecoveryApprovalConcurrencyIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PaymentRecoveryApprovalRepository approvals;
    @Autowired PaymentRecoveryProposalRepository proposals;
    @Autowired PaymentRecoveryCaseRepository cases;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PasswordEncoder encoder;

    @Test
    void twoDifferentSuperAdminsCanApproveOneHighValueProposalOnlyOnce() throws Exception {
        Instant now = Instant.now();
        PlatformOperatorAccount requester = account("approval-requester@example.com", now);
        PlatformOperatorAccount firstApprover = account("approval-first@example.com", now);
        PlatformOperatorAccount secondApprover = account("approval-second@example.com", now);
        PaymentRecoveryCase recoveryCase = cases.saveAndFlush(PaymentRecoveryCase.open(
                "28199", RecoveryKind.REFUND_FAILED, ResultStatus.FAILED,
                300_000L, 0L, 300_000L, "KRW", Set.of(RecoveryAction.RETRY_REFUND),
                "port********race", 3L, 4L, 5L, now));
        recoveryCase.beginInvestigation(1L, now);
        cases.saveAndFlush(recoveryCase);
        PaymentRecoveryProposal proposal = proposals.saveAndFlush(PaymentRecoveryProposal.propose(
                recoveryCase.getPublicId(), 1L, 2L, RecoveryAction.RETRY_REFUND,
                200_001L, 200_001L, 300_000L, "KRW", 3L, 4L, 5L,
                "b".repeat(64), requester.getId(), 1L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                UUID.randomUUID().toString(), now));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> outcomes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> approve(proposal, firstApprover.getId(), ready, start));
            var second = pool.submit(() -> approve(proposal, secondApprover.getId(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(approvals.findByCasePublicIdAndProposalVersion(
                recoveryCase.getPublicId(), proposal.getProposalVersion())).isPresent();
        assertThat(approvals.count()).isEqualTo(1L);
    }

    private PlatformOperatorAccount account(String email, Instant now) {
        return accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                email, encoder.encode("Password1!"), email, now.plusSeconds(600)));
    }

    private boolean approve(PaymentRecoveryProposal proposal, long approverId,
                            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    approvals.saveAndFlush(PaymentRecoveryApproval.approve(
                            proposal, approverId, 1L, Set.of(PlatformOperatorRole.SUPER_ADMIN),
                            Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE),
                            UUID.randomUUID().toString(), Instant.now())));
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
