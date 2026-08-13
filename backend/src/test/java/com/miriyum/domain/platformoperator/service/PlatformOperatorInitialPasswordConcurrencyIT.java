package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.dto.auth.InitialPasswordChangeRequest;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
        "miriyum.platform-operator.temporary-password.validity=PT10M",
        "miriyum.platform-operator.temporary-password.max-failures=3"
})
class PlatformOperatorInitialPasswordConcurrencyIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PlatformOperatorPasswordChangeTransaction transaction;
    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorAuthEventRepository events;
    @Autowired PasswordEncoder encoder;

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
}
