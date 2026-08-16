package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
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
@Tag("integration-shard-d")
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
class LastSuperAdminConcurrencyIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired LastSuperAdminPolicy policy;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired PlatformOperatorPermissionGrantRepository permissions;
    @Autowired PlatformOperatorAuthEventRepository authEvents;
    @Autowired PasswordEncoder encoder;

    @Test
    void concurrentMutationAttemptsCannotRemoveTheSingletonSuperAdministrator() throws Exception {
        permissions.deleteAll();
        roles.deleteAll();
        authEvents.deleteAll();
        accounts.deleteAll();
        PlatformOperatorAccount first = account("super-one@example.com");
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                first.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> remove(first.getId(), ready, start));
            var b = executor.submit(() -> remove(first.getId(), ready, start));
            ready.await();
            start.countDown();
            results = List.of(a.get(), b.get());
        }

        assertThat(results).containsExactly(false, false);
        assertThat(roles.countActiveSuperAdministrators()).isEqualTo(1L);
    }

    @Test
    void suspendedOrdinaryOperatorGrantCanBeRemovedWithoutAffectingSingleton() {
        permissions.deleteAll();
        roles.deleteAll();
        authEvents.deleteAll();
        accounts.deleteAll();
        PlatformOperatorAccount active = account("active-super@example.com");
        PlatformOperatorAccount suspended = account("suspended-operator@example.com");
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                active.getId(), PlatformOperatorRole.SUPER_ADMIN, Instant.now()));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                suspended.getId(), PlatformOperatorRole.ONBOARDING_REVIEWER, Instant.now()));
        suspended.suspend();
        accounts.saveAndFlush(suspended);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            policy.assertRemovable(suspended.getId());
            roles.deleteByPlatformOperatorAccountIdAndRole(
                    suspended.getId(), PlatformOperatorRole.ONBOARDING_REVIEWER);
        });

        assertThat(roles.existsByPlatformOperatorAccountIdAndRole(
                suspended.getId(), PlatformOperatorRole.ONBOARDING_REVIEWER)).isFalse();
        assertThat(roles.countActiveSuperAdministrators()).isEqualTo(1L);
    }

    private PlatformOperatorAccount account(String email) {
        return accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                email, encoder.encode("Password1!"), email, Instant.now().plusSeconds(600)));
    }

    private boolean remove(long target, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            try {
                return new TransactionTemplate(transactions).execute(status -> {
                    policy.assertRemovable(target);
                    roles.deleteByPlatformOperatorAccountIdAndRole(target, PlatformOperatorRole.SUPER_ADMIN);
                    return true;
                });
            } catch (ServiceException exception) {
                assertThat(exception.getErrorCode()).isEqualTo(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED);
                return false;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
