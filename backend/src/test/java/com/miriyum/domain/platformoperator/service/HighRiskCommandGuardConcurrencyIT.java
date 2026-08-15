package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorReauthenticationApproval;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.AdminCaseAssignmentRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorReauthenticationApprovalRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class HighRiskCommandGuardConcurrencyIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired HighRiskCommandGuard guard;
    @Autowired ReauthenticationService reauthentication;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired PlatformOperatorPermissionGrantRepository permissions;
    @Autowired AdminCaseAssignmentRepository assignments;
    @Autowired PlatformOperatorReauthenticationApprovalRepository approvals;
    @Autowired PlatformOperatorAuthEventRepository authEvents;
    @Autowired PasswordEncoder encoder;

    @Test
    void oneApprovalCanBeConsumedByExactlyOneConcurrentCommand() throws Exception {
        approvals.deleteAll();
        assignments.deleteAll();
        permissions.deleteAll();
        roles.deleteAll();
        authEvents.deleteAll();
        accounts.deleteAll();
        Instant now = Instant.now();
        PlatformOperatorAccount account = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "guard-race@example.com", encoder.encode("Password1!"), "guard-race", now.plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                account.getId(), PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR, now));
        assignments.saveAndFlush(AdminCaseAssignment.assign(
                AdminCaseType.PAYMENT_RECOVERY, "case-1", 1L, account.getId(), now.plusSeconds(600), now));
        String plaintextApproval = "single-use-approval-value";
        String sessionId = "session-race";
        approvals.saveAndFlush(PlatformOperatorReauthenticationApproval.issue(
                ReauthenticationService.sha256(plaintextApproval), account.getId(),
                AdminCommandPurpose.PAYMENT_RECOVERY, AdminTargetType.PAYMENT_RECOVERY_CASE, "target-1",
                reauthentication.sessionFingerprint(sessionId), 1L, now, now.plusSeconds(300)));
        HighRiskCommandRequest request = new HighRiskCommandRequest(
                new PlatformOperatorPrincipal(account.getId(), account.getEmail(), sessionId, 1L, 1L, false),
                PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE,
                AdminCaseType.PAYMENT_RECOVERY, "case-1", 1L,
                AdminCommandPurpose.PAYMENT_RECOVERY, AdminTargetType.PAYMENT_RECOVERY_CASE, "target-1",
                plaintextApproval, "corr-race");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> authorize(request, ready, start));
            var second = executor.submit(() -> authorize(request, ready, start));
            ready.await();
            start.countDown();
            outcomes = List.of(first.get(), second.get());
        }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    }

    private boolean authorize(HighRiskCommandRequest request, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            try {
                return new TransactionTemplate(transactions).execute(status -> {
                    guard.authorize(request);
                    return true;
                });
            } catch (ServiceException exception) {
                assertThat(exception.getErrorCode()).isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
                return false;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
