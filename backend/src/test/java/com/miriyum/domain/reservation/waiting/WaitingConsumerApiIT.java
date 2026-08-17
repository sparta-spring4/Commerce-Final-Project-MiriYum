package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
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
import com.miriyum.global.exception.CommonErrorCode;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
            "miriyum.waiting.closure.initial-delay-ms=600000",
            "miriyum.waiting.compensation.initial-delay-ms=600000",
            "miriyum.payment.cursor-secret=test-history-cursor-secret-with-enough-entropy",
            "miriyum.payment.portone.api-secret=test-api-secret",
            "miriyum.payment.portone.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
            "miriyum.payment.portone.store-id=store-1"
        })
class WaitingConsumerApiIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 17);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WaitingCreationService creationService;
    @Autowired WaitingConsumerCommandFacade consumerCommandFacade;
    @Autowired WaitingConsumerQueryService consumerQueryService;
    @Autowired WaitingCommandFacade operatorCommandFacade;
    @Autowired WaitingTeamRepository teams;
    @Autowired WaitingActiveMembershipRepository memberships;
    @Autowired StoreRepository stores;
    @Autowired StoreOperatorAccountRepository operators;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean WaitingOperatingIntervalPort intervalPort;

    @BeforeEach
    void clean() {
        when(intervalPort.lockCurrent(anyLong(), any(LocalDate.class), any(Instant.class)))
                .thenAnswer(invocation -> List.of(openInterval(
                        invocation.getArgument(0), invocation.getArgument(1))));
        for (String table : new String[]{
                "waiting_status_events", "waiting_transition_audits", "waiting_active_memberships",
                "waiting_teams", "waiting_queue_sequences", "idempotency_commands",
                "waiting_setting_audits", "waiting_settings", "store_tag_assignment", "stores",
                "store_operator_accounts", "consumer_accounts"}) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    @Test
    void registrationAndConsumerCancellationReplayWithoutDuplicateEffects() {
        Fixture fixture = fixture();
        IdempotencyKey createKey = key(1);

        WaitingCommandResult created = creationService.create(
                fixture.firstStoreId(), fixture.consumerId(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, createKey);
        WaitingCommandResult replayed = creationService.create(
                fixture.firstStoreId(), fixture.consumerId(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, createKey);
        long teamId = Long.parseLong(created.data().waitingTeamId());

        assertThat(replayed).isEqualTo(created);
        WaitingConsumerSnapshot current = consumerQueryService.getCurrent(fixture.consumerId());
        assertThat(current.waitingTeamId()).isEqualTo(Long.toString(teamId));
        assertThat(current.teamsAhead()).isZero();
        assertThat(count("waiting_teams")).isOne();
        assertThat(count("waiting_active_memberships")).isOne();
        assertThatThrownBy(() -> creationService.create(
                fixture.firstStoreId(), fixture.consumerId(), BUSINESS_DATE, 3,
                WaitingSource.REMOTE, createKey))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        WaitingCommandResult cancelled = consumerCommandFacade.cancel(
                fixture.consumerId(), teamId, key(2), new WaitingTeamTransitionRequest(0L));
        WaitingCommandResult cancelReplay = consumerCommandFacade.cancel(
                fixture.consumerId(), teamId, key(2), new WaitingTeamTransitionRequest(0L));

        assertThat(cancelReplay).isEqualTo(cancelled);
        assertThat(teams.findById(teamId).orElseThrow().getStatus())
                .isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(count("waiting_active_memberships")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_transition_audits
                 WHERE waiting_team_id=? AND actor_type='CONSUMER' AND after_status='CANCELLED'
                """, Long.class, teamId)).isOne();
    }

    @Test
    void parallelRegistrationKeepsOneActiveWaitingAcrossStores() throws Exception {
        Fixture fixture = fixture();

        List<Attempt<WaitingCommandResult>> attempts = runTogether(2, index -> creationService.create(
                index == 0 ? fixture.firstStoreId() : fixture.secondStoreId(),
                fixture.consumerId(), BUSINESS_DATE, 2, WaitingSource.REMOTE, key(10 + index)));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded()).map(Attempt::failure))
                .singleElement()
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS));
        assertThat(count("waiting_teams")).isOne();
        assertThat(count("waiting_active_memberships")).isOne();
    }

    @Test
    void anotherConsumerCannotDiscoverOrCancelTheWaitingTeam() {
        Fixture fixture = fixture();
        WaitingCommandResult created = creationService.create(
                fixture.firstStoreId(), fixture.consumerId(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(15));
        long teamId = Long.parseLong(created.data().waitingTeamId());
        long otherConsumerId = createConsumer();

        assertThatThrownBy(() -> consumerCommandFacade.cancel(
                otherConsumerId, teamId, key(16), new WaitingTeamTransitionRequest(0L)))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_TEAM_NOT_FOUND));

        assertThat(teams.findById(teamId).orElseThrow().getStatus())
                .isEqualTo(WaitingTeamStatus.WAITING);
        assertThat(count("waiting_active_memberships")).isOne();
        assertThat(count("waiting_transition_audits")).isOne();
        assertThat(count("waiting_status_events")).isOne();
    }

    @Test
    void consumerCancelAndOperatorCallRaceCommitsExactlyOneTransition() throws Exception {
        Fixture fixture = fixture();
        WaitingCommandResult created = creationService.create(
                fixture.firstStoreId(), fixture.consumerId(), BUSINESS_DATE, 2,
                WaitingSource.REMOTE, key(20));
        long teamId = Long.parseLong(created.data().waitingTeamId());

        List<Attempt<WaitingCommandResult>> attempts = runTogether(2, index -> index == 0
                ? consumerCommandFacade.cancel(
                        fixture.consumerId(), teamId, key(21), new WaitingTeamTransitionRequest(0L))
                : operatorCommandFacade.call(
                        fixture.operatorId(), fixture.firstStoreId(), teamId, key(22),
                        new WaitingTeamTransitionRequest(0L)));

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded()).map(Attempt::failure))
                .singleElement()
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT));
        WaitingTeam stored = teams.findById(teamId).orElseThrow();
        assertThat(stored.getStatus()).isIn(WaitingTeamStatus.CANCELLED, WaitingTeamStatus.CALLED);
        assertThat(stored.getVersion()).isOne();
        assertThat(count("waiting_transition_audits")).isEqualTo(2);
        assertThat(count("waiting_status_events")).isEqualTo(2);
        assertThat(count("waiting_active_memberships"))
                .isEqualTo(stored.getStatus() == WaitingTeamStatus.CALLED ? 1 : 0);
    }

    private Fixture fixture() {
        long operatorId = operators.saveAndFlush(StoreOperatorAccount.create(
                "consumer-api-" + UUID.randomUUID() + "@example.com", "hashed", "owner")).getId();
        long firstStoreId = createStore(operatorId);
        long secondStoreId = createStore(operatorId);
        return new Fixture(operatorId, firstStoreId, secondStoreId, createConsumer());
    }

    private long createConsumer() {
        String email = "waiting-consumer-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO consumer_accounts (email,password_hash,name,status,created_at,updated_at) "
                + "VALUES (?,'hashed','consumer','ACTIVE',NOW(6),NOW(6))", email);
        return jdbc.queryForObject(
                "SELECT consumer_account_id FROM consumer_accounts WHERE email=?", Long.class, email);
    }

    private long createStore(long operatorId) {
        long storeId = stores.saveAndFlush(Store.create(
                operatorId, registrationNumber(), BusinessType.CAFE, "Consumer Waiting Store", "",
                Region.SEOUL, "Seoul", "CAFE_BAKERY", Set.of(), true, true, true,
                "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        jdbc.update("""
                INSERT INTO waiting_settings (
                    store_id, enabled, reception_mode, advance_open_minutes, version,
                    lock_version, created_at, updated_at
                ) VALUES (?, TRUE, 'MANUAL', 60, 1, 0, NOW(6), NOW(6))
                """, storeId);
        return storeId;
    }

    private WaitingOperatingInterval openInterval(long storeId, LocalDate businessDate) {
        return new WaitingOperatingInterval(
                storeId, "consumer-api-" + storeId, 1L, businessDate,
                businessDate.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                Instant.now().plus(Duration.ofHours(1)), "Asia/Seoul");
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
                    results.add(future.get(20, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    throw new AssertionError(
                            "concurrent participant failed outside captured boundary", failure.getCause());
                }
            }
            return results;
        }
    }

    private IdempotencyKey key(int suffix) {
        return IdempotencyKey.parse(String.format("550e8400-e29b-41d4-a716-%012d", suffix));
    }

    private String registrationNumber() {
        return Long.toString(1000000000L
                + Math.abs(UUID.randomUUID().getMostSignificantBits() % 8999999999L));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @FunctionalInterface
    private interface ConcurrentWork<T> { T run(int index); }

    private record Fixture(long operatorId, long firstStoreId, long secondStoreId, long consumerId) {}

    private record Attempt<T>(T result, RuntimeException failure) {
        static <T> Attempt<T> success(T result) { return new Attempt<>(result, null); }
        static <T> Attempt<T> failure(RuntimeException failure) { return new Attempt<>(null, failure); }
        boolean succeeded() { return failure == null; }
    }
}
