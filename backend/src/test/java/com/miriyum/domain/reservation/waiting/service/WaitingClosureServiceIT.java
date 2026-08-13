package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureCommandResult;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingClosureJobItemRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingClosureJobRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.waiting.closure.initial-delay-ms=600000"
})
@Import(WaitingClosureServiceIT.MutableClockConfig.class)
class WaitingClosureServiceIT {
    private static final Instant BASE_TIME = Instant.parse("2026-08-13T00:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440202");

    @Container static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingClosureService service;
    @Autowired WaitingTeamRepository teams;
    @Autowired WaitingActiveMembershipRepository memberships;
    @Autowired StoreRepository stores;
    @Autowired StoreOperatorAccountRepository operators;
    @Autowired JdbcTemplate jdbc;
    @Autowired WaitingClosureJobItemRepository items;
    @Autowired WaitingClosureJobRepository jobs;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MutableClock mutableClock;

    @BeforeEach
    void clean() {
        mutableClock.set(BASE_TIME);
        for (String table : new String[]{"waiting_status_events", "waiting_transition_audits",
                "waiting_closure_job_items", "waiting_closure_jobs", "waiting_active_memberships",
                "waiting_teams", "waiting_queue_sequences", "idempotency_commands",
                "store_tag_assignment", "stores", "store_operator_accounts", "consumer_accounts"}) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    @Test
    void concurrentTransactionsProveVisibilitySingleClaimAndFencing() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch createdButUncommitted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<WaitingClosureCommandResult> creator = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        WaitingClosureCommandResult result = service.startClosure(
                                fixture.operatorId, fixture.storeId, KEY, 7L);
                        createdButUncommitted.countDown();
                        await(allowCommit);
                        return result;
                    }));
            assertThat(createdButUncommitted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<List<WaitingClosureClaim>> invisibleClaim = executor.submit(() ->
                    service.claimPendingItems("runner-before-commit", 100, Duration.ofSeconds(30)));
            assertThat(invisibleClaim.get(5, TimeUnit.SECONDS)).isEmpty();
            allowCommit.countDown();
            assertThat(creator.get(5, TimeUnit.SECONDS).httpStatus()).isEqualTo(202);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Future<List<WaitingClosureClaim>> first = concurrentClaim(
                    executor, ready, release, "runner-a");
            Future<List<WaitingClosureClaim>> second = concurrentClaim(
                    executor, ready, release, "runner-b");
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            List<WaitingClosureClaim> firstResult = first.get(5, TimeUnit.SECONDS);
            List<WaitingClosureClaim> secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.size(), secondResult.size())).containsExactlyInAnyOrder(0, 1);
            WaitingClosureClaim original = firstResult.isEmpty() ? secondResult.getFirst() : firstResult.getFirst();
            assertThat(jdbc.queryForObject("SELECT lease_owner FROM waiting_closure_job_items", String.class))
                    .isEqualTo(original.owner());
            assertThat(jdbc.queryForObject("SELECT claim_token FROM waiting_closure_job_items", Long.class))
                    .isEqualTo(original.token());

            mutableClock.advance(Duration.ofSeconds(31));
            String newOwner = original.owner().equals("runner-a") ? "runner-b" : "runner-a";
            WaitingClosureClaim reclaimed = service.claimPendingItems(
                    newOwner, 100, Duration.ofSeconds(30)).getFirst();
            assertThat(reclaimed.token()).isGreaterThan(original.token());

            CountDownLatch staleReady = new CountDownLatch(2);
            CountDownLatch staleRelease = new CountDownLatch(1);
            Future<Boolean> staleComplete = executor.submit(() -> {
                staleReady.countDown(); await(staleRelease); return service.processClaimedItem(original);
            });
            Future<Boolean> staleFailure = executor.submit(() -> {
                staleReady.countDown(); await(staleRelease); return service.recordFailure(original, false);
            });
            assertThat(staleReady.await(5, TimeUnit.SECONDS)).isTrue();
            staleRelease.countDown();
            assertThat(staleComplete.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(staleFailure.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(service.processClaimedItem(reclaimed)).isTrue();

            assertThat(count("waiting_active_memberships")).isZero();
            assertThat(count("waiting_transition_audits")).isOne();
            assertThat(count("waiting_status_events")).isOne();
            assertThat(jdbc.queryForObject("SELECT completed_team_count FROM waiting_closure_jobs", Long.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class)).isEqualTo("COMPLETED");
        } finally {
            allowCommit.countDown();
        }
    }

    @Test
    void realTransactionReplaysSameJobAndFixedItemsWithoutDuplicates() {
        Fixture fixture = fixture();

        WaitingClosureCommandResult first = startClosure(
                fixture.operatorId, fixture.storeId, KEY, 7L);
        WaitingClosureCommandResult replay = startClosure(
                fixture.operatorId, fixture.storeId, KEY, 7L);

        assertThat(first.httpStatus()).isEqualTo(202);
        assertThat(replay).isEqualTo(first);
        assertThat(count("waiting_closure_jobs")).isOne();
        assertThat(count("waiting_closure_job_items")).isOne();
        assertThat(count("idempotency_commands")).isOne();

        assertThatThrownBy(() -> startClosure(
                fixture.operatorId, fixture.storeId, KEY, 8L))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(count("waiting_closure_jobs")).isOne();
    }

    @Test
    void publicHandoffRequiresOuterTransactionAndRollsBackAtomically() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> service.startClosure(fixture.operatorId, fixture.storeId, KEY, 7L))
                .isInstanceOf(IllegalTransactionStateException.class);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            service.startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
            status.setRollbackOnly();
        });

        assertThat(count("waiting_closure_jobs")).isZero();
        assertThat(count("waiting_closure_job_items")).isZero();
        assertThat(count("idempotency_commands")).isZero();
    }

    @Test
    void workerClosesActiveTeamAtomicallyExactlyOnceAndCompletesJob() {
        Fixture fixture = fixture();
        startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        WaitingClosureClaim itemId = claim("owner-a").getFirst();

        service.processClaimedItem(itemId);

        assertThat(jdbc.queryForObject("SELECT status FROM waiting_teams", String.class))
                .isEqualTo("CLOSED_BY_STORE");
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(count("waiting_transition_audits")).isOne();
        assertThat(count("waiting_status_events")).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_team_count FROM waiting_closure_jobs", Long.class))
                .isOne();
    }

    @Test
    void failedItemRollsBackThenRestartRecoveryRequeuesAndCompletes() {
        Fixture fixture = fixture();
        startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        WaitingClosureClaim itemId = claim("owner-a").getFirst();
        jdbc.execute("DELETE FROM waiting_active_memberships");

        assertThatThrownBy(() -> service.processClaimedItem(itemId))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_teams", String.class)).isEqualTo("WAITING");
        assertThat(count("waiting_transition_audits")).isZero();
        assertThat(count("waiting_status_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("PROCESSING");

        long teamId = jdbc.queryForObject("SELECT waiting_team_id FROM waiting_teams", Long.class);
        long consumerId = jdbc.queryForObject("SELECT consumer_account_id FROM waiting_teams", Long.class);
        memberships.saveAndFlush(WaitingActiveMembership.create(
                fixture.storeId, consumerId, teamId, Instant.now()));
        int attemptsBeforeRecovery = jdbc.queryForObject(
                "SELECT attempt_count FROM waiting_closure_job_items", Integer.class);
        expireLease();
        WaitingClosureClaim reclaimed = claim("owner-b").getFirst();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM waiting_closure_job_items", Integer.class))
                .isEqualTo(attemptsBeforeRecovery + 1);
        service.processClaimedItem(reclaimed);
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    void workerSkipsTeamThatBecameTerminalAfterSnapshot() {
        Fixture fixture = fixture();
        startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        WaitingClosureClaim itemId = claim("owner-a").getFirst();
        jdbc.execute("DELETE FROM waiting_active_memberships");
        jdbc.update("UPDATE waiting_teams SET status='CANCELLED', cancelled_at=?, version=version+1",
                java.sql.Timestamp.from(mutableClock.instant()));

        service.processClaimedItem(itemId);

        assertThat(jdbc.queryForObject("SELECT status FROM waiting_teams", String.class)).isEqualTo("CANCELLED");
        assertThat(count("waiting_transition_audits")).isZero();
        assertThat(count("waiting_status_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    void recoveryReconcilesExhaustedProcessingItemWithoutFourthClaim() {
        Fixture fixture = fixture();
        startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        WaitingClosureClaim itemId = claim("owner-a").getFirst();
        service.recordFailure(itemId, true);
        itemId = claim("owner-a").getFirst();
        service.recordFailure(itemId, true);
        itemId = claim("owner-a").getFirst();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM waiting_closure_job_items", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("PROCESSING");

        expireLease();
        assertThat(claim("owner-b")).isEmpty();

        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM waiting_closure_job_items", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT reconciliation_required_team_count FROM waiting_closure_jobs", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM waiting_closure_jobs", Boolean.class))
                .isTrue();
        assertThat(claim("owner-c")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM waiting_closure_job_items", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void durableLeasePreventsLiveStealAndFencesExpiredOwner() {
        Fixture fixture = fixture();
        startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        WaitingClosureClaim first = claim("runner-a").getFirst();

        assertThat(claim("runner-b")).isEmpty();
        expireLease();
        WaitingClosureClaim reclaimed = claim("runner-b").getFirst();
        assertThat(reclaimed.token()).isGreaterThan(first.token());
        assertThat(service.processClaimedItem(first)).isFalse();
        assertThat(service.recordFailure(first, false)).isFalse();

        assertThat(service.processClaimedItem(reclaimed)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_team_count FROM waiting_closure_jobs", Long.class)).isOne();
        assertThat(count("waiting_transition_audits")).isOne();
        assertThat(count("waiting_status_events")).isOne();
    }

    @Test
    void expiredLeaseRejectsOwnerAtExactBoundaryBeforeReclaim() {
        Fixture fixture = fixture();
        startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);
        WaitingClosureClaim expired = claim("runner-a").getFirst();

        mutableClock.advance(Duration.ofSeconds(30));

        assertThat(service.processClaimedItem(expired)).isFalse();
        assertThat(service.recordFailure(expired, true)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM waiting_closure_job_items", String.class))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject(
                "SELECT lease_owner FROM waiting_closure_job_items", String.class))
                .isEqualTo("runner-a");
        assertThat(jdbc.queryForObject("SELECT status FROM waiting_teams", String.class))
                .isEqualTo("WAITING");
        assertThat(count("waiting_transition_audits")).isZero();
        assertThat(count("waiting_status_events")).isZero();
    }

    @Test
    void laterItemGetsFreshLeaseAfterEarlierWorkConsumesMoreThanLeaseDuration() {
        Fixture fixture = fixture();
        addSecondWaitingTeam(fixture.storeId);
        startClosure(fixture.operatorId, fixture.storeId, KEY, 7L);

        WaitingClosureClaim first = service.claimPendingItems(
                "runner-a", 1, Duration.ofSeconds(30)).getFirst();
        assertThat(service.processClaimedItem(first)).isTrue();
        mutableClock.advance(Duration.ofSeconds(31));

        WaitingClosureClaim second = service.claimPendingItems(
                "runner-a", 1, Duration.ofSeconds(30)).getFirst();
        mutableClock.advance(Duration.ofSeconds(29));

        assertThat(service.processClaimedItem(second)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT completed_team_count FROM waiting_closure_jobs", Long.class))
                .isEqualTo(2L);
    }

    private Fixture fixture() {
        long operatorId = operators.saveAndFlush(
                StoreOperatorAccount.create("closure@example.com", "hashed", "owner")).getId();
        long storeId = stores.saveAndFlush(Store.create(operatorId, "1234567899", BusinessType.CAFE,
                "Closure Store", "", Region.SEOUL, "Seoul", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        jdbc.update("INSERT INTO consumer_accounts (email,password_hash,name,status,created_at,updated_at) "
                + "VALUES ('closure-consumer@example.com','hashed','consumer','ACTIVE',NOW(6),NOW(6))");
        long consumerId = jdbc.queryForObject(
                "SELECT consumer_account_id FROM consumer_accounts WHERE email='closure-consumer@example.com'",
                Long.class);
        WaitingTeam team = teams.saveAndFlush(WaitingTeam.create(storeId, consumerId,
                LocalDate.of(2026, 8, 13), 2, WaitingSource.REMOTE, 1L,
                mutableClock.instant().minusSeconds(60)));
        memberships.saveAndFlush(WaitingActiveMembership.create(
                storeId, consumerId, team.getId(), mutableClock.instant().minusSeconds(60)));
        return new Fixture(operatorId, storeId);
    }

    private void addSecondWaitingTeam(long storeId) {
        jdbc.update("INSERT INTO consumer_accounts (email,password_hash,name,status,created_at,updated_at) "
                + "VALUES ('closure-consumer-2@example.com','hashed','consumer','ACTIVE',NOW(6),NOW(6))");
        long consumerId = jdbc.queryForObject(
                "SELECT consumer_account_id FROM consumer_accounts "
                        + "WHERE email='closure-consumer-2@example.com'",
                Long.class);
        WaitingTeam team = teams.saveAndFlush(WaitingTeam.create(
                storeId, consumerId, LocalDate.of(2026, 8, 13), 2,
                WaitingSource.REMOTE, 2L, mutableClock.instant().minusSeconds(60)));
        memberships.saveAndFlush(WaitingActiveMembership.create(
                storeId, consumerId, team.getId(), mutableClock.instant().minusSeconds(60)));
    }

    private void expireLease() {
        mutableClock.advance(Duration.ofSeconds(31));
    }

    private WaitingClosureCommandResult startClosure(
            long operatorId, long storeId, IdempotencyKey key, long version) {
        return new TransactionTemplate(transactionManager).execute(status ->
                service.startClosure(operatorId, storeId, key, version));
    }

    private java.util.List<WaitingClosureClaim> claim(String owner) {
        return service.claimPendingItems(owner, 100, java.time.Duration.ofSeconds(30));
    }

    private Future<List<WaitingClosureClaim>> concurrentClaim(
            ExecutorService executor, CountDownLatch ready, CountDownLatch release, String owner) {
        return executor.submit(() -> {
            ready.countDown(); await(release);
            return service.claimPendingItems(owner, 100, Duration.ofSeconds(30));
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record Fixture(long operatorId, long storeId) {}

    @TestConfiguration
    static class MutableClockConfig {
        @Bean @Primary MutableClock waitingTestClock() { return new MutableClock(BASE_TIME); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        MutableClock(Instant initial) { current = new AtomicReference<>(initial); }
        void set(Instant value) { current.set(value); }
        void advance(Duration duration) { current.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
