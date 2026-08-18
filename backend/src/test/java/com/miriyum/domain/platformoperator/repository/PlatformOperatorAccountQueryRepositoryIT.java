package com.miriyum.domain.platformoperator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuthEvent;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorRoleGrant;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.menu.schedule.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class PlatformOperatorAccountQueryRepositoryIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40")
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PlatformOperatorAccountRepository accounts;
    @Autowired PlatformOperatorRoleGrantRepository roles;
    @Autowired PlatformOperatorPermissionGrantRepository permissions;
    @Autowired PlatformOperatorAuthEventRepository authEvents;
    @Autowired PasswordEncoder encoder;

    @Test
    void filtersSearchesSortsAndUsesOnlyLatestSuccessfulLogin() {
        permissions.deleteAll();
        roles.deleteAll();
        authEvents.deleteAll();
        accounts.deleteAll();

        PlatformOperatorAccount alpha = account("alpha@example.com", "Same Name");
        PlatformOperatorAccount beta = account("beta@example.com", "Same Name");
        PlatformOperatorAccount suspended = account("suspended@example.com", "Suspended");
        suspended.suspend();
        accounts.saveAndFlush(suspended);
        roles.saveAndFlush(PlatformOperatorRoleGrant.create(
                alpha.getId(), PlatformOperatorRole.AUDIT_READER, Instant.EPOCH));

        authEvents.saveAndFlush(event(alpha.getId(), PlatformOperatorAuthEventOutcome.SUCCESS,
                "login-success-old", Instant.parse("2026-08-17T01:00:00Z")));
        authEvents.saveAndFlush(event(alpha.getId(), PlatformOperatorAuthEventOutcome.FAILURE,
                "login-failure-new", Instant.parse("2026-08-17T03:00:00Z")));
        authEvents.saveAndFlush(event(alpha.getId(), PlatformOperatorAuthEventOutcome.SUCCESS,
                "login-success-new", Instant.parse("2026-08-17T02:00:00Z")));
        authEvents.saveAndFlush(event(beta.getId(), PlatformOperatorAuthEventOutcome.SUCCESS,
                "beta-login-success", Instant.parse("2026-08-17T00:30:00Z")));

        var active = search("ACTIVE", null, null, null, "displayName", "asc", 0, 10);
        assertThat(active.getContent()).extracting(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .containsExactly(alpha.getId(), beta.getId());
        assertThat(active.getContent().get(0).getLastLoginAt())
                .isEqualTo(java.time.LocalDateTime.parse("2026-08-17T02:00:00"));
        assertThat(active.getContent().get(1).getLastLoginAt())
                .isEqualTo(java.time.LocalDateTime.parse("2026-08-17T00:30:00"));

        assertThat(search(null, null, null, null, "lastLoginAt", "asc", 0, 10).getContent())
                .extracting(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .containsExactly(beta.getId(), alpha.getId(), suspended.getId());
        assertThat(search(null, null, null, null, "lastLoginAt", "desc", 0, 10).getContent())
                .extracting(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .containsExactly(alpha.getId(), beta.getId(), suspended.getId());
        assertThat(search(null, null, null, null, "status", "desc", 1, 1).getContent())
                .extracting(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .containsExactly(alpha.getId());

        assertThat(search(null, "AUDIT_READER", null, null, "operatorId", "asc", 0, 10).getContent())
                .extracting(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .containsExactly(alpha.getId());
        assertThat(search(null, null, "ALPHA@EXAMPLE", null, "operatorId", "asc", 0, 10).getContent())
                .extracting(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .containsExactly(alpha.getId());
        assertThat(search(null, null, "same name", null, "operatorId", "asc", 1, 1).getContent())
                .extracting(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .containsExactly(beta.getId());
        assertThat(search(null, null, String.valueOf(suspended.getId()), suspended.getId(),
                "operatorId", "asc", 0, 10).getContent())
                .extracting(PlatformOperatorAccountRepository.AccountQueryRow::getOperatorId)
                .containsExactly(suspended.getId());
    }

    private PlatformOperatorAccount account(String email, String displayName) {
        return accounts.saveAndFlush(PlatformOperatorAccount.createTemporary(
                email, encoder.encode("Password1!"), displayName, Instant.now().plusSeconds(600)));
    }

    private PlatformOperatorAuthEvent event(
            long accountId, PlatformOperatorAuthEventOutcome outcome, String key, Instant occurredAt) {
        return PlatformOperatorAuthEvent.record(accountId, PlatformOperatorAuthEventType.LOGIN,
                1L, 1L, outcome, key, occurredAt);
    }

    private org.springframework.data.domain.Page<PlatformOperatorAccountRepository.AccountQueryRow> search(
            String status, String role, String query, Long queryId,
            String sortField, String sortDirection, int page, int size) {
        return accounts.searchAccounts(status, role, query, queryId, sortField, sortDirection,
                PageRequest.of(page, size));
    }
}
