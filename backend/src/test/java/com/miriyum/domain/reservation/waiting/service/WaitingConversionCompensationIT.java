package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
})
@Import(WaitingConversionCompensationIT.MutableClockConfig.class)
class WaitingConversionCompensationIT {
    private static final Instant BASE_TIME = Instant.parse("2026-08-14T00:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired WaitingConversionCompensationService service;
    @Autowired WaitingTeamRepository teams;
    @Autowired StoreRepository stores;
    @Autowired StoreOperatorAccountRepository operators;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @BeforeEach
    void clean() {
        clock.set(BASE_TIME);
        for (String table : new String[]{
                "waiting_conversion_compensations",
                "payment_webhook_receipts", "payment_ledger_entries", "payment_refunds",
                "payment_attempts", "payments",
                "waiting_status_events", "waiting_transition_audits",
                "waiting_closure_job_items", "waiting_closure_jobs",
                "waiting_active_memberships", "waiting_teams", "waiting_queue_sequences",
                "store_tag_assignment", "stores", "store_operator_accounts", "consumer_accounts"}) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    @Test
    void schemaRejectsInvalidPaymentIdentityAndNonPositiveRefundSource() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO waiting_conversion_compensations (
                    waiting_team_id, payment_id, refund_amount_minor, currency,
                    refund_policy_version, source_event_id, idempotency_key, reason_code,
                    status, attempt_count, next_attempt_at, claim_token, created_at
                ) VALUES (?, 'payment-101', 0, 'KRW', 3, 'source:bad',
                    '550e8400-e29b-41d4-a716-446655440401', 'WAITING_CANCELLED',
                    'PENDING', 0, NOW(6), 0, NOW(6))
                """, fixture.teamId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void concurrentRecordRequiredConvergesToOneRowAndRejectsImmutableConflict()
            throws Exception {
        Fixture fixture = fixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> first = executor.submit(() -> recordConcurrently(
                    fixture.teamId(), 12_000L, ready, release));
            Future<Long> second = executor.submit(() -> recordConcurrently(
                    fixture.teamId(), 12_000L, ready, release));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertThat(countCompensations()).isOne();

        assertThatThrownBy(() -> record(fixture.teamId(), 12_001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting");
        assertThat(jdbc.queryForObject(
                "SELECT refund_amount_minor FROM waiting_conversion_compensations",
                Long.class)).isEqualTo(12_000L);
    }

    @Test
    void concurrentWorkersClaimOnceThenExpiredLeaseReclaimsAndFencesStaleOwner()
            throws Exception {
        Fixture fixture = fixture();
        record(fixture.teamId(), 12_000L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<WaitingCompensationClaim> a;
        List<WaitingCompensationClaim> b;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<WaitingCompensationClaim>> first = executor.submit(
                    () -> claimConcurrently("worker-a", ready, release));
            Future<List<WaitingCompensationClaim>> second = executor.submit(
                    () -> claimConcurrently("worker-b", ready, release));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            a = first.get(5, TimeUnit.SECONDS);
            b = second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(List.of(a.size(), b.size())).containsExactlyInAnyOrder(0, 1);
        WaitingCompensationClaim stale = a.isEmpty() ? b.getFirst() : a.getFirst();

        clock.advance(Duration.ofSeconds(31));
        WaitingCompensationClaim reclaimed = service.claimPending(
                "worker-c", 1, Duration.ofSeconds(30), 0L).getFirst();

        assertThat(reclaimed.token()).isGreaterThan(stale.token());
        assertThat(service.recordFailure(stale, true)).isFalse();
        assertThat(service.recordFailure(stale, false)).isFalse();
        assertThat(service.recordFailure(reclaimed, false)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM waiting_conversion_compensations", String.class))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(service.countReconciliationRequired()).isOne();
    }

    private Long recordConcurrently(
            long teamId,
            long amount,
            CountDownLatch ready,
            CountDownLatch release
    ) {
        ready.countDown();
        await(release);
        return record(teamId, amount);
    }

    private List<WaitingCompensationClaim> claimConcurrently(
            String owner,
            CountDownLatch ready,
            CountDownLatch release
    ) {
        ready.countDown();
        await(release);
        return service.claimPending(owner, 1, Duration.ofSeconds(30), 0L);
    }

    private long record(long teamId, long amount) {
        return record(teamId, "101", amount, "440401");
    }

    private long record(long teamId, String paymentId, long amount, String keySuffix) {
        return service.recordRequired(
                teamId, paymentId, amount, "KRW", 3L,
                "waiting-conversion-cancelled:" + teamId,
                "550e8400-e29b-41d4-a716-446655" + keySuffix,
                "WAITING_CANCELLED");
    }

    private Fixture fixture() {
        long operatorId = operators.saveAndFlush(
                StoreOperatorAccount.create("compensation@example.com", "hashed", "owner"))
                .getId();
        long storeId = stores.saveAndFlush(Store.create(
                operatorId, "1234567899", "Compensation Store", "", Region.SEOUL, "Seoul", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        jdbc.update("""
                INSERT INTO consumer_accounts (
                    email, password_hash, name, status, created_at, updated_at
                ) VALUES ('compensation-consumer@example.com', 'hashed', 'consumer',
                          'ACTIVE', NOW(6), NOW(6))
                """);
        long consumerId = jdbc.queryForObject(
                "SELECT consumer_account_id FROM consumer_accounts "
                        + "WHERE email='compensation-consumer@example.com'",
                Long.class);
        WaitingTeam team = teams.saveAndFlush(WaitingTeam.create(
                storeId, consumerId, LocalDate.of(2026, 8, 14), 2,
                WaitingSource.REMOTE, 1L, BASE_TIME.minusSeconds(60)));
        return new Fixture(team.getId(), consumerId);
    }

    private long countCompensations() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM waiting_conversion_compensations", Long.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(long teamId, long consumerId) { }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock compensationClock() {
            return new MutableClock(BASE_TIME);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant value) { current.set(value); }
        void advance(Duration duration) { current.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
