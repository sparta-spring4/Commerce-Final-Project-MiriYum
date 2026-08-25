package com.miriyum.domain.reservation.waiting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.notification.config.NotificationSettings.RuntimePolicy;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.service.NotificationDeliveryService;
import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.notification.WaitingNotificationSourceAdapter;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.service.WaitingStatusEventDispatcher;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.notification.worker.enabled=false",
            "miriyum.waiting.compensation.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.store.schedule.activation-delay-ms=3600000",
            "miriyum.menu.schedule.initial-delay-ms=3600000"
        }
)
class WaitingNotificationRuntimeIT {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired StoreOperatorAccountRepository operators;
    @Autowired StoreRepository stores;
    @Autowired WaitingTeamRepository teams;
    @Autowired WaitingStatusEventRepository statusEvents;
    @Autowired WaitingStatusEventDispatcher dispatcher;
    @Autowired NotificationTaskRecorder recorder;
    @Autowired NotificationDeliveryService delivery;
    @MockitoSpyBean WaitingNotificationSourceAdapter waitingSource;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : List.of(
                "notification_task_transition_audits",
                "notification_channel_attempts",
                "notification_tasks",
                "waiting_entry_imminent_events",
                "waiting_status_events",
                "waiting_transition_audits",
                "waiting_active_memberships",
                "waiting_teams",
                "waiting_queue_sequences",
                "stores",
                "store_operator_accounts",
                "consumer_accounts")) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    void entryImminentIsOneShotAndCalledSupersedesOnlyTheOlderTask() {
        Fixture fixture = fixture(4);
        statusEvents.saveAndFlush(WaitingStatusEvent.pending(
                fixture.teamIds().getFirst(), 1L, WaitingTeamStatus.WAITING,
                Instant.parse("2026-08-16T03:00:00Z")));

        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(count("waiting_entry_imminent_events")).isEqualTo(3);
        assertThat(purposeCount("WAITING_ENTRY_IMMINENT")).isEqualTo(3);
        assertThat(jdbc.queryForList(
                "SELECT status FROM waiting_teams ORDER BY queue_sequence", String.class))
                .containsOnly("WAITING");

        Instant calledAt = Instant.now().minusSeconds(1);
        transactions.executeWithoutResult(ignored -> {
            WaitingTeam first = teams.findByIdForUpdate(fixture.teamIds().getFirst()).orElseThrow();
            first.call(first.getVersion(), calledAt);
            statusEvents.saveAndFlush(WaitingStatusEvent.pending(
                    first.getId(), first.getVersion() + 1L, first.getStatus(), calledAt));
        });
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(purposeCount("WAITING_CALLED")).isOne();

        int converged = delivery.deliverDueBatch(policy());
        assertThat(converged)
                .as("tasks=%s", jdbc.queryForList("""
                        SELECT purpose, status, attempt_count, scheduled_at,
                               next_attempt_at, last_error_code
                          FROM notification_tasks ORDER BY notification_id
                        """))
                .isEqualTo(4);

        assertThat(jdbc.queryForObject("""
                SELECT status FROM notification_tasks
                 WHERE resource_id = ? AND purpose = 'WAITING_ENTRY_IMMINENT'
                """, String.class, fixture.teamIds().getFirst())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForMap("""
                SELECT status, last_error_code FROM notification_tasks
                 WHERE resource_id = ? AND purpose = 'WAITING_CALLED'
                """, fixture.teamIds().getFirst()))
                .containsEntry("status", "DELIVERED")
                .containsEntry("last_error_code", null);
        assertThat(jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND, occurred_at, expires_at)
                  FROM notification_tasks WHERE purpose = 'WAITING_CALLED'
                """, Integer.class)).isEqualTo(600);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_entry_imminent_events
                 WHERE waiting_team_id = ?
                """, Integer.class, fixture.teamIds().get(3))).isZero();
    }

    @Test
    void notificationConflictRollsBackReevaluationAndStatusPublicationTogether() {
        Fixture fixture = fixture(1);
        long teamId = fixture.teamIds().getFirst();
        Instant calledAt = Instant.now().minusSeconds(1);
        WaitingStatusEvent called = transactions.execute(ignored -> {
            WaitingTeam team = teams.findByIdForUpdate(teamId).orElseThrow();
            team.call(team.getVersion(), calledAt);
            return statusEvents.saveAndFlush(WaitingStatusEvent.pending(
                    teamId, team.getVersion() + 1L, team.getStatus(), calledAt));
        });

        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(
                calledAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), ZoneOffset.UTC);
        transactions.executeWithoutResult(ignored -> {
            recorder.record(event(
                    "waiting-entry-imminent:" + teamId,
                    NotificationPurpose.WAITING_ENTRY_IMMINENT,
                    fixture.consumerIds().getFirst(),
                    teamId,
                    1L,
                    "WAITING",
                    occurredAt,
                    null));
            recorder.record(event(
                    "waiting-status-event:" + called.getId(),
                    NotificationPurpose.WAITING_CALLED,
                    fixture.consumerIds().getFirst(),
                    teamId,
                    called.getEventSequence(),
                    "WAITING",
                    occurredAt,
                    occurredAt.plusMinutes(10)));
        });
        long entryVersionBefore = jdbc.queryForObject("""
                SELECT version FROM notification_tasks
                 WHERE purpose = 'WAITING_ENTRY_IMMINENT'
                """, Long.class);

        assertThatThrownBy(dispatcher::dispatchNext).isInstanceOf(ServiceException.class);

        assertThat(jdbc.queryForObject("""
                SELECT publication_state FROM waiting_status_events
                 WHERE waiting_status_event_id = ?
                """, String.class, called.getId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM notification_tasks
                 WHERE purpose = 'WAITING_ENTRY_IMMINENT'
                """, Long.class)).isEqualTo(entryVersionBefore);
    }

    @Test
    void committedTransitionSupersedesDeliveryEvenBeforeDispatcherRuns() throws Exception {
        Fixture fixture = fixture(1);
        long teamId = fixture.teamIds().getFirst();
        statusEvents.saveAndFlush(WaitingStatusEvent.pending(
                teamId, 1L, WaitingTeamStatus.WAITING, Instant.now().minusSeconds(1)));
        assertThat(dispatcher.dispatchNext()).isTrue();

        CountDownLatch transitionChanged = new CountDownLatch(1);
        CountDownLatch allowTransitionCommit = new CountDownLatch(1);
        CountDownLatch sourceReturned = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            sourceReturned.countDown();
            return result;
        }).when(waitingSource).readContextForDelivery(
                any(NotificationPurpose.class), anyString(), anyLong(), anyString());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var transition = executor.submit(() -> {
                transactions.executeWithoutResult(ignored -> {
                    WaitingTeam team = teams.findByIdForUpdate(teamId).orElseThrow();
                    Instant calledAt = Instant.now();
                    team.call(team.getVersion(), calledAt);
                    statusEvents.saveAndFlush(WaitingStatusEvent.pending(
                            teamId, team.getVersion() + 1L, team.getStatus(), calledAt));
                    transitionChanged.countDown();
                    await(allowTransitionCommit);
                });
                return null;
            });
            assertThat(transitionChanged.await(5, TimeUnit.SECONDS)).isTrue();

            var deliveryResult = executor.submit(() -> delivery.deliverDueBatch(policy()));
            assertThat(sourceReturned.await(500, TimeUnit.MILLISECONDS))
                    .as("source read must wait for the in-flight Waiting transition")
                    .isFalse();

            allowTransitionCommit.countDown();
            transition.get(5, TimeUnit.SECONDS);
            assertThat(sourceReturned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(deliveryResult.get(5, TimeUnit.SECONDS)).isOne();
        }

        assertThat(jdbc.queryForMap("""
                SELECT status, last_error_code FROM notification_tasks
                 WHERE resource_id = ? AND purpose = 'WAITING_ENTRY_IMMINENT'
                """, teamId))
                .containsEntry("status", "CANCELLED")
                .containsEntry("last_error_code", "SOURCE_SUPERSEDED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_status_events
                 WHERE waiting_team_id = ? AND public_status = 'CALLED'
                   AND publication_state = 'PENDING'
                """, Integer.class, teamId)).isOne();
    }

    private Fixture fixture(int teamCount) {
        long operatorId = operators.saveAndFlush(StoreOperatorAccount.create(
                "waiting-notification@example.com", "hashed", "owner")).getId();
        long storeId = stores.saveAndFlush(Store.create(
                operatorId, "1234567899", "미리윰 강남", "",
                Region.SEOUL, "서울", "CAFE_BAKERY", Set.of(), true, true, true,
                "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1")).getId();
        List<Long> consumerIds = new ArrayList<>();
        List<Long> teamIds = new ArrayList<>();
        for (int index = 1; index <= teamCount; index++) {
            jdbc.update("""
                    INSERT INTO consumer_accounts (
                        email, password_hash, name, status, created_at, updated_at
                    ) VALUES (?, 'hashed', 'consumer', 'ACTIVE', NOW(6), NOW(6))
                    """, "waiting-notification-" + index + "@example.com");
            long consumerId = jdbc.queryForObject(
                    "SELECT consumer_account_id FROM consumer_accounts WHERE email = ?",
                    Long.class,
                    "waiting-notification-" + index + "@example.com");
            WaitingTeam team = teams.saveAndFlush(WaitingTeam.create(
                    storeId, consumerId, LocalDate.of(2026, 8, 16), 2,
                    WaitingSource.REMOTE, index,
                    Instant.parse("2026-08-16T02:50:00Z").plusSeconds(index)));
            consumerIds.add(consumerId);
            teamIds.add(team.getId());
        }
        return new Fixture(storeId, List.copyOf(consumerIds), List.copyOf(teamIds));
    }

    private static NotificationSourceEventV1 event(
            String sourceEventId,
            NotificationPurpose purpose,
            long consumerId,
            long teamId,
            long sequence,
            String sourceState,
            OffsetDateTime occurredAt,
            OffsetDateTime expiresAt
    ) {
        return new NotificationSourceEventV1(
                sourceEventId,
                NotificationSourceDomain.WAITING,
                purpose,
                Long.toString(consumerId),
                1L,
                NotificationResourceType.WAITING_TEAM,
                Long.toString(teamId),
                sequence,
                sourceState,
                occurredAt,
                occurredAt,
                expiresAt,
                null,
                sourceEventId
        );
    }

    private static RuntimePolicy policy() {
        return new RuntimePolicy(
                "waiting-runtime-it-v1",
                "waiting-runtime-it",
                10,
                Duration.ofSeconds(30),
                3,
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int purposeCount(String purpose) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_tasks WHERE purpose = ?",
                Integer.class,
                purpose
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("latch interrupted", interrupted);
        }
    }

    private record Fixture(long storeId, List<Long> consumerIds, List<Long> teamIds) {
    }
}
