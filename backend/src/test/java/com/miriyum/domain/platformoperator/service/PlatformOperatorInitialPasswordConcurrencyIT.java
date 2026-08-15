package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.logindelay.LoginAttempt;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.platformoperator.config.PlatformOperatorAuthProperties;
import com.miriyum.domain.platformoperator.dto.auth.InitialPasswordChangeRequest;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorSessionManager;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
        "miriyum.platform-operator.temporary-password.max-failures=3"
})
class PlatformOperatorInitialPasswordConcurrencyIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PlatformOperatorPasswordChangeTransaction transaction;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorAuthEventRepository events;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformOperatorAuthService authService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void oldTemporaryPasswordCannotIssueNormalSessionAfterConcurrentChangeCommits() throws Exception {
        events.deleteAll();
        accounts.deleteAll();
        PlatformOperatorAccount account = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "login-change-race@example.com", encoder.encode("Password1!"), "race",
                Instant.now().plusSeconds(600)));
        CountDownLatch passwordMatched = new CountDownLatch(1);
        CountDownLatch resumeLogin = new CountDownLatch(1);
        PasswordEncoder blockingEncoder = new BlockingPasswordEncoder(encoder, account.getPasswordHash(),
                passwordMatched, resumeLogin);
        LoginDelayGuard delay = mock(LoginDelayGuard.class);
        PlatformOperatorSessionManager sessions = mock(PlatformOperatorSessionManager.class);
        PlatformOperatorAuthEventRecorder eventRecorder = mock(PlatformOperatorAuthEventRecorder.class);
        when(delay.tryAcquireAttempt(TokenNamespace.PLATFORM_OPERATOR, account.getId()))
                .thenReturn(LoginAttempt.acquired("attempt"));
        when(delay.completeAttempt(any(), any(Long.class), any(), any(Boolean.class))).thenReturn(true);
        PlatformOperatorAuthService racingService = new PlatformOperatorAuthService(
                accounts, blockingEncoder, new PasswordPolicy(), delay, sessions, transaction, eventRecorder,
                properties(), Clock.fixed(Instant.now(), ZoneOffset.UTC));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var login = executor.submit(() -> racingService.login(
                    new LoginRequest(account.getEmail(), "Password1!")));
            assertThat(passwordMatched.await(10, TimeUnit.SECONDS)).isTrue();
            transaction.change(account.getId(),
                    new InitialPasswordChangeRequest("Password1!", "Changed2@", "Changed2@"));
            resumeLogin.countDown();

            assertThatThrownBy(login::get)
                    .hasCauseInstanceOf(ServiceException.class)
                    .satisfies(error -> assertThat(((ServiceException) error.getCause()).getErrorCode())
                            .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        }
        verify(sessions, never()).issue(any(), any(Long.class), any(Long.class), any(Boolean.class));
    }

    @Test
    void arbitraryUnknownEmailsShareOneBoundedDelayRow() {
        jdbc.update("DELETE FROM login_failure_delays WHERE account_namespace = ? AND account_id < 0",
                "platform-operator");
        for (int index = 0; index < 12; index++) {
            try {
                authService.login(new LoginRequest("missing-" + index + "@example.com", "Wrong1!"));
            } catch (ServiceException ignored) {
                // Every unknown credential has the same public failure contract.
            }
        }
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_failure_delays WHERE account_namespace = ? AND account_id < 0",
                Integer.class, "platform-operator");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void concurrentTemporaryPasswordFailuresAreAllCountedAtomically() throws Exception {
        events.deleteAll();
        accounts.deleteAll();
        PlatformOperatorAccount account = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "failures@example.com", encoder.encode("Password1!"), "failures", Instant.now().plusSeconds(600)));
        int attempts = 8;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(attempts)) {
            var futures = java.util.stream.IntStream.range(0, attempts)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return accounts.incrementTemporaryPasswordFailure(account.getId());
                    })).toList();
            ready.await();
            start.countDown();
            for (var future : futures) assertThat(future.get()).isEqualTo(1);
        }
        assertThat(accounts.findById(account.getId()).orElseThrow().getTemporaryPasswordFailureCount())
                .isEqualTo(attempts);
    }

    @Test
    void exactlyOneConcurrentInitialPasswordChangeCommits() throws Exception {
        events.deleteAll();
        accounts.deleteAll();
        PlatformOperatorAccount account = accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                "race@example.com", encoder.encode("Password1!"), "race", Instant.now().plusSeconds(600)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> first = change(account.getId(), "Changed2@", ready, start);
            Callable<String> second = change(account.getId(), "Different3#", ready, start);
            var futures = List.of(executor.submit(first), executor.submit(second));
            ready.await();
            start.countDown();
            List<String> results = futures.stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).toList();

            assertThat(results).containsExactlyInAnyOrder("SUCCESS", "COMMON_008");
        }
        PlatformOperatorAccount saved = accounts.findById(account.getId()).orElseThrow();
        assertThat(saved.getSessionVersion()).isEqualTo(2L);
        assertThat(events.findAllByAccountIdOrderByOccurredAtAsc(account.getId())).hasSize(1);
    }

    private Callable<String> change(
            Long id, String newPassword, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return runChange(id, newPassword);
        };
    }

    private String runChange(Long id, String newPassword) {
        try {
            transaction.change(id, new InitialPasswordChangeRequest("Password1!", newPassword, newPassword));
            return "SUCCESS";
        } catch (ServiceException exception) {
            return exception.getErrorCode().getCode();
        }
    }

    private static PlatformOperatorAuthProperties properties() {
        PlatformOperatorAuthProperties properties = new PlatformOperatorAuthProperties();
        properties.setEnabled(true);
        properties.getTemporaryPassword().setValidity(Duration.ofMinutes(10));
        properties.getTemporaryPassword().setMaxFailures(3);
        return properties;
    }

    private static final class BlockingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate;
        private final String blockedHash;
        private final CountDownLatch passwordMatched;
        private final CountDownLatch resumeLogin;

        private BlockingPasswordEncoder(PasswordEncoder delegate, String blockedHash,
                CountDownLatch passwordMatched, CountDownLatch resumeLogin) {
            this.delegate = delegate;
            this.blockedHash = blockedHash;
            this.passwordMatched = passwordMatched;
            this.resumeLogin = resumeLogin;
        }

        @Override public String encode(CharSequence rawPassword) { return delegate.encode(rawPassword); }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            boolean matches = delegate.matches(rawPassword, encodedPassword);
            if (matches && blockedHash.equals(encodedPassword)) {
                passwordMatched.countDown();
                try {
                    if (!resumeLogin.await(10, TimeUnit.SECONDS)) throw new AssertionError("login did not resume");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return matches;
        }
    }
}
