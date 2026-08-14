package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@Timeout(30)
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.waiting.closure.initial-delay-ms=600000"
        })
class WaitingLedgerConcurrencyIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 13);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingCreationService creationService;
    @Autowired WaitingCommandFacade commandFacade;
    @Autowired WaitingClosureService closureService;
    @Autowired WaitingTeamRepository teams;
    @Autowired WaitingActiveMembershipRepository memberships;
    @Autowired StoreRepository stores;
    @Autowired StoreOperatorAccountRepository operators;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        for (String table : new String[]{"waiting_status_events", "waiting_transition_audits",
                "waiting_closure_job_items", "waiting_closure_jobs", "waiting_active_memberships",
                "waiting_teams", "waiting_queue_sequences", "idempotency_commands",
                "store_tag_assignment", "stores", "store_operator_accounts", "consumer_accounts"}) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    @Test
    void parallelCreationAllocatesUniqueMonotonicSequencesAndMemberships() throws Exception {
        Fixture fixture = fixture(8);
        List<Attempt<WaitingCommandResult>> attempts = runTogether(8, index -> creationService.create(
                fixture.storeId(), fixture.consumerIds().get(index), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(100 + index)));

        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(jdbc.queryForList(
                "SELECT queue_sequence FROM waiting_teams ORDER BY queue_sequence", Long.class))
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(count("waiting_active_memberships")).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT next_sequence FROM waiting_queue_sequences", Long.class))
                .isEqualTo(9L);
        assertThat(count("waiting_transition_audits")).isEqualTo(8);
        assertThat(count("waiting_status_events")).isEqualTo(8);
    }

    @Test
    void crossStoreDuplicateActiveMembershipRacePreservesWinnerThenAllowsCreationAfterCancellation() throws Exception {
        Fixture fixture = fixture(1);
        long consumerId = fixture.consumerIds().getFirst();
        long otherStoreId = createStore(fixture.operatorId());
        List<Attempt<WaitingCommandResult>> attempts = runTogether(2, index -> creationService.create(
                index == 0 ? fixture.storeId() : otherStoreId,
                consumerId, BUSINESS_DATE, 2, WaitingSource.REMOTE, key(200 + index)));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded()).map(Attempt::failure))
                .singleElement()
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS));
        WaitingTeam winner = teams.findById(Long.parseLong(attempts.stream()
                .filter(Attempt::succeeded)
                .findFirst()
                .orElseThrow()
                .result()
                .data()
                .waitingTeamId())).orElseThrow();
        long winnerStoreId = winner.getStoreId();
        long losingStoreId = winnerStoreId == fixture.storeId() ? otherStoreId : fixture.storeId();
        assertThat(winner.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(winner.getVersion()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT store_id FROM waiting_active_memberships WHERE consumer_account_id=?", Long.class, consumerId))
                .isEqualTo(winnerStoreId);
        assertThat(count("waiting_teams")).isOne();
        assertThat(count("waiting_active_memberships")).isOne();
        assertThat(count("waiting_transition_audits")).isOne();
        assertThat(count("waiting_status_events")).isOne();
        assertThat(count("idempotency_commands")).isOne();

        commandFacade.cancel(fixture.operatorId(), winnerStoreId, winner.getId(), key(202),
                new WaitingTeamTransitionRequest(winner.getVersion()));

        assertThat(teams.findById(winner.getId()).orElseThrow().getStatus()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(count("waiting_active_memberships")).isZero();

        WaitingCommandResult retry = creationService.create(losingStoreId, consumerId, BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(203));

        assertThat(retry.data().storeId()).isEqualTo(Long.toString(losingStoreId));
        assertThat(teams.findById(Long.parseLong(retry.data().waitingTeamId())).orElseThrow().getStatus())
                .isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(count("waiting_teams")).isEqualTo(2);
        assertThat(count("waiting_active_memberships")).isOne();
        assertThat(count("waiting_transition_audits")).isEqualTo(3);
        assertThat(count("waiting_status_events")).isEqualTo(3);
        assertThat(count("idempotency_commands")).isEqualTo(3);
    }

    @Test
    void sameVersionCallCancelRaceCommitsExactlyOneTransition() throws Exception {
        Fixture fixture = fixture(1);
        WaitingCommandResult created = creationService.create(fixture.storeId(), fixture.consumerIds().getFirst(),
                BUSINESS_DATE, 2, WaitingSource.REMOTE, key(300));
        long teamId = Long.parseLong(created.data().waitingTeamId());

        List<Attempt<WaitingCommandResult>> attempts = runTogether(2, index -> index == 0
                ? commandFacade.call(fixture.operatorId(), fixture.storeId(), teamId, key(301),
                        new WaitingTeamTransitionRequest(0L))
                : commandFacade.cancel(fixture.operatorId(), fixture.storeId(), teamId, key(302),
                        new WaitingTeamTransitionRequest(0L)));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded()).map(Attempt::failure))
                .singleElement()
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT));
        WaitingTeam stored = teams.findById(teamId).orElseThrow();
        assertThat(stored.getVersion()).isOne();
        assertThat(stored.getStatus()).isIn(WaitingTeamStatus.CALLED, WaitingTeamStatus.CANCELLED);
        assertThat(count("waiting_transition_audits")).isEqualTo(2);
        assertThat(count("waiting_status_events")).isEqualTo(2);
        assertThat(count("waiting_active_memberships"))
                .isEqualTo(stored.getStatus() == WaitingTeamStatus.CALLED ? 1 : 0);
    }

    @Test
    void concurrentCallsRemainBlockedWhileEarlierCallIsUnresolved() throws Exception {
        Fixture fixture = fixture(3);
        createTeams(fixture, 350);
        List<Long> teamIds = jdbc.queryForList(
                "SELECT waiting_team_id FROM waiting_teams ORDER BY queue_sequence", Long.class);
        commandFacade.call(
                fixture.operatorId(), fixture.storeId(), teamIds.getFirst(), key(360),
                new WaitingTeamTransitionRequest(0L));

        List<Attempt<WaitingCommandResult>> attempts = runTogether(2, index ->
                commandFacade.call(
                        fixture.operatorId(),
                        fixture.storeId(),
                        teamIds.get(index + 1),
                        key(361 + index),
                        new WaitingTeamTransitionRequest(0L)));

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.succeeded()).isFalse();
            assertThat(attempt.failure()).isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_NOT_FIFO_HEAD));
        });
        assertThat(jdbc.queryForList(
                "SELECT status FROM waiting_teams ORDER BY queue_sequence", String.class))
                .containsExactly("CALLED", "WAITING", "WAITING");
        assertThat(count("waiting_transition_audits")).isEqualTo(4);
        assertThat(count("waiting_status_events")).isEqualTo(4);
    }

    @Test
    void concurrentSameClosureKeyProducesOneImmutableJobAndSnapshot() throws Exception {
        Fixture fixture = fixture(2);
        createTeams(fixture, 400);
        IdempotencyKey closureKey = key(410);

        List<Attempt<WaitingClosureCommandResult>> attempts = runTogether(2, ignored ->
                new TransactionTemplate(transactionManager).execute(status ->
                        closureService.startClosure(fixture.operatorId(), fixture.storeId(), closureKey, 7L)));

        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(attempts.get(0).result()).isEqualTo(attempts.get(1).result());
        assertThat(count("waiting_closure_jobs")).isOne();
        assertThat(count("waiting_closure_job_items")).isEqualTo(2);
        assertThat(count("idempotency_commands")).isEqualTo(3);
    }

    @Test
    void convertingTeamCancelAndClosureRaceProducesOneTerminalEffectAndRemovesMembership() throws Exception {
        Fixture fixture = fixture(1);
        WaitingCommandResult created = creationService.create(
                fixture.storeId(), fixture.consumerIds().getFirst(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(450));
        long teamId = Long.parseLong(created.data().waitingTeamId());
        jdbc.update("UPDATE waiting_teams SET status='RESERVATION_CONVERTING' WHERE waiting_team_id=?", teamId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                closureService.startClosure(fixture.operatorId(), fixture.storeId(), key(451), 7L));
        WaitingClosureClaim claim = closureService.claimPendingItems(
                "converting-race", 1, Duration.ofSeconds(30), 0L).getFirst();

        List<Attempt<Object>> attempts = runTogether(2, index -> index == 0
                ? commandFacade.cancel(fixture.operatorId(), fixture.storeId(), teamId, key(452),
                        new WaitingTeamTransitionRequest(0L))
                : closureService.processClaimedItem(claim));

        assertThat(attempts.get(1).succeeded()).isTrue();
        assertThat(attempts.get(1).result()).isEqualTo(Boolean.TRUE);
        if (!attempts.getFirst().succeeded()) {
            assertThat(attempts.getFirst().failure()).isInstanceOfSatisfying(ServiceException.class,
                    failure -> assertThat(failure.getErrorCode())
                            .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT));
        }
        WaitingTeam stored = teams.findById(teamId).orElseThrow();
        assertThat(stored.getStatus()).isIn(WaitingTeamStatus.CANCELLED, WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(stored.getVersion()).isOne();
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(count("waiting_transition_audits")).isEqualTo(2);
        assertThat(count("waiting_status_events")).isEqualTo(2);
    }

    @Test
    void committedPartialBatchRetryResumesWithoutDuplicateEffects() {
        Fixture fixture = fixture(2);
        createTeams(fixture, 500);
        new TransactionTemplate(transactionManager).execute(status ->
                closureService.startClosure(fixture.operatorId(), fixture.storeId(), key(510), 8L));
        List<WaitingClosureClaim> claimed = closureService.claimPendingItems(
                "concurrency", 100, Duration.ofSeconds(30), 0L);
        assertThat(claimed).hasSize(2);

        closureService.processClaimedItem(claimed.getFirst());
        long secondTeamId = jdbc.queryForObject(
                "SELECT waiting_team_id FROM waiting_closure_job_items WHERE waiting_closure_job_item_id=?",
                Long.class, claimed.get(1).itemId());
        long secondConsumerId = jdbc.queryForObject(
                "SELECT consumer_account_id FROM waiting_teams WHERE waiting_team_id=?", Long.class, secondTeamId);
        jdbc.update("DELETE FROM waiting_active_memberships WHERE waiting_team_id=?", secondTeamId);

        assertThatThrownBy(() -> closureService.processClaimedItem(claimed.get(1)))
                .isInstanceOf(ServiceException.class);
        closureService.recordFailure(claimed.get(1), true);
        memberships.saveAndFlush(WaitingActiveMembership.create(
                fixture.storeId(), secondConsumerId, secondTeamId, Instant.now()));
        WaitingClosureClaim retried = closureService.claimPendingItems(
                "concurrency", 100, Duration.ofSeconds(30), 0L).getFirst();
        closureService.processClaimedItem(retried);

        assertThat(jdbc.queryForObject("SELECT status FROM waiting_closure_jobs", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_team_count FROM waiting_closure_jobs", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT status FROM waiting_closure_job_items ORDER BY waiting_team_id",
                String.class)).containsExactly("COMPLETED", "COMPLETED");
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(count("waiting_transition_audits")).isEqualTo(4);
        assertThat(count("waiting_status_events")).isEqualTo(4);
    }

    private void createTeams(Fixture fixture, int keyBase) {
        for (int index = 0; index < fixture.consumerIds().size(); index++) {
            creationService.create(fixture.storeId(), fixture.consumerIds().get(index), BUSINESS_DATE,
                    2, WaitingSource.REMOTE, key(keyBase + index));
        }
    }

    private Fixture fixture(int consumerCount) {
        long operatorId = operators.saveAndFlush(
                StoreOperatorAccount.create("concurrency-" + UUID.randomUUID() + "@example.com", "hashed", "owner"))
                .getId();
        long storeId = createStore(operatorId);
        List<Long> consumerIds = new ArrayList<>();
        for (int index = 0; index < consumerCount; index++) {
            String email = "waiting-concurrency-" + UUID.randomUUID() + "@example.com";
            jdbc.update("INSERT INTO consumer_accounts (email,password_hash,name,status,created_at,updated_at) "
                    + "VALUES (?,'hashed','consumer','ACTIVE',NOW(6),NOW(6))", email);
            consumerIds.add(jdbc.queryForObject(
                    "SELECT consumer_account_id FROM consumer_accounts WHERE email=?", Long.class, email));
        }
        return new Fixture(operatorId, storeId, consumerIds);
    }

    private long createStore(long operatorId) {
        return stores.saveAndFlush(Store.create(operatorId, registrationNumber(), BusinessType.CAFE,
                "Concurrency Store", "", Region.SEOUL, "Seoul", "CAFE_BAKERY", Set.of(),
                true, true, true, "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
    }

    private <T> List<Attempt<T>> runTogether(int participants, ConcurrentWork<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(participants)) {
            List<Future<Attempt<T>>> futures = new ArrayList<>();
            for (int index = 0; index < participants; index++) {
                int taskIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency start barrier timed out");
                    }
                    try {
                        return Attempt.success(work.run(taskIndex));
                    } catch (RuntimeException failure) {
                        return Attempt.failure(failure);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt<T>> results = new ArrayList<>();
            for (Future<Attempt<T>> future : futures) {
                try {
                    results.add(future.get(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS));
                } catch (ExecutionException failure) {
                    throw new AssertionError("concurrent participant failed outside captured boundary", failure.getCause());
                }
            }
            return results;
        }
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format("550e8400-e29b-41d4-a716-%012d", suffix));
    }

    private String registrationNumber() {
        return Long.toString(1000000000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 8999999999L));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @FunctionalInterface
    private interface ConcurrentWork<T> {
        T run(int index);
    }

    private record Fixture(long operatorId, long storeId, List<Long> consumerIds) {}

    private record Attempt<T>(T result, RuntimeException failure) {
        static <T> Attempt<T> success(T result) { return new Attempt<>(result, null); }
        static <T> Attempt<T> failure(RuntimeException failure) { return new Attempt<>(null, failure); }
        boolean succeeded() { return failure == null; }
    }
}
