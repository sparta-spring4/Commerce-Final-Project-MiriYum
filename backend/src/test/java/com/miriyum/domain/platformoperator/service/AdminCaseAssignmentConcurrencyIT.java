package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.AdminCaseAssignmentRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
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
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false"
})
class AdminCaseAssignmentConcurrencyIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired AdminCaseAssignmentVerifier verifier;
    @Autowired AdminCaseAssignmentManager manager;
    @Autowired AdminCaseAssignmentRepository assignments;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PasswordEncoder encoder;

    @Test
    void assignmentCannotBeClosedBetweenVerificationAndBusinessCommit() throws Exception {
        assignments.deleteAll();
        accounts.deleteAll();
        Instant now = Instant.now();
        PlatformOperatorAccount account = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "assignment-race@example.com", encoder.encode("Password1!"),
                "assignment-race", now.plusSeconds(600)));
        AdminCaseAssignmentRequest request = new AdminCaseAssignmentRequest(
                AdminCaseType.PAYMENT_RECOVERY, "case-race", 1L, account.getId());
        assignments.saveAndFlush(AdminCaseAssignment.assign(
                request.caseType(), request.caseId(), request.caseVersion(), request.operatorId(),
                now.plusSeconds(600), now));

        CountDownLatch verified = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var business = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                verifier.verify(request);
                verified.countDown();
                await(allowCommit);
            }));
            assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();

            var close = executor.submit(() -> manager.close(request));
            assertThat(close.isDone()).isFalse();
            allowCommit.countDown();
            business.get(10, TimeUnit.SECONDS);
            close.get(10, TimeUnit.SECONDS);
        }

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                verifier.verify(request)))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to commit verified command");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
