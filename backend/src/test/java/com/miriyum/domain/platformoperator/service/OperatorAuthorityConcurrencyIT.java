package com.miriyum.domain.platformoperator.service;

import static com.miriyum.domain.platformoperator.enums.PlatformOperatorRole.ONBOARDING_REVIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorPermissionGrantRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
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
class OperatorAuthorityConcurrencyIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired OperatorAuthorityService authority;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired PlatformOperatorPermissionGrantRepository permissions;
    @Autowired PlatformOperatorAuthEventRepository authEvents;
    @Autowired PasswordEncoder encoder;

    @Test
    @DisplayName("동시 역할 회수는 계정 잠금으로 한 번만 version을 증가시키고 구 version을 거부한다")
    void concurrentRevocationsAdvanceVersionOnceAndRejectStaleRequests() throws Exception {
        permissions.deleteAll();
        roles.deleteAll();
        authEvents.deleteAll();
        accounts.deleteAll();
        PlatformOperatorAccount account = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "authority-race@example.com",
                encoder.encode("Password1!"),
                "authority-race",
                Instant.now().plusSeconds(600)));
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(account.getId(), ONBOARDING_REVIEWER, Instant.now()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> revoke(account.getId(), ready, start));
            var second = executor.submit(() -> revoke(account.getId(), ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(accounts.findById(account.getId()).orElseThrow().getAuthorityVersion()).isEqualTo(2L);
        assertThat(roles.findAllByPlatformOperatorAccountId(account.getId())).isEmpty();
        assertThatThrownBy(() -> authority.requireCurrentAuthority(account.getId(), 1L))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
    }

    private void revoke(long accountId, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            authority.revokeRole(accountId, ONBOARDING_REVIEWER);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
