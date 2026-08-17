package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        }
)
class NotificationRecorderIntegrationTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl()
                        + "?connectionTimeZone=Asia/Seoul&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired NotificationTaskRecorder recorder;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM notification_task_transition_audits");
        jdbcTemplate.execute("DELETE FROM notification_channel_attempts");
        jdbcTemplate.execute("DELETE FROM notification_tasks");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status, created_at, updated_at
                ) VALUES (11, 'notification-owner@example.com', 'hash', '알림소유자',
                          'ACTIVE', NOW(6), NOW(6))
                """);
    }

    @Test
    void replayConvergesToOneTaskAttemptAndInitialTransitionWithoutOverwritingCorrelation() {
        NotificationTaskReceipt first = record(event("correlation-first", "CONFIRMED"));
        NotificationTaskReceipt replay = record(event("correlation-replay", "CONFIRMED"));

        assertThat(first.duplicate()).isFalse();
        assertThat(replay).isEqualTo(new NotificationTaskReceipt(first.notificationId(), true));
        assertThat(count("notification_tasks")).isEqualTo(1);
        assertThat(count("notification_channel_attempts")).isEqualTo(1);
        assertThat(count("notification_task_transition_audits")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM notification_tasks", String.class))
                .isEqualTo("correlation-first");
    }

    @Test
    void plusNineImmediateNotificationIsStoredAsTheSameUtcInstant() {
        record(event("correlation-plus-nine", "CONFIRMED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT scheduled_at FROM notification_tasks",
                LocalDateTime.class))
                .isEqualTo(LocalDateTime.parse("2026-08-12T01:02:03"));
    }

    @Test
    void sameLogicalEventWithDifferentPayloadRaisesConflictAndRollsBackProducerWork() {
        record(event("correlation-first", "CONFIRMED"));

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            jdbcTemplate.update("""
                    INSERT INTO consumer_accounts (
                        consumer_account_id, email, password_hash, name, status, created_at, updated_at
                    ) VALUES (12, 'must-rollback@example.com', 'hash', '롤백대상',
                              'ACTIVE', NOW(6), NOW(6))
                    """);
            recorder.record(event("correlation-conflict", "CANCELLED"));
        })).isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(NotificationErrorCode.SOURCE_EVENT_CONFLICT));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_accounts WHERE consumer_account_id = 12",
                Integer.class)).isZero();
        assertThat(count("notification_tasks")).isEqualTo(1);
    }

    @Test
    void concurrentReplaysStillCreateOnlyOneLedgerEntry() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<NotificationTaskReceipt> call =
                    () -> transactions.execute(status -> recorder.record(
                            event("correlation-concurrent", "CONFIRMED")));
            List<NotificationTaskReceipt> receipts = executor.invokeAll(List.of(call, call)).stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(receipts).extracting(NotificationTaskReceipt::notificationId)
                    .containsOnly(receipts.getFirst().notificationId());
            assertThat(receipts).extracting(NotificationTaskReceipt::duplicate)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(count("notification_tasks")).isEqualTo(1);
            assertThat(count("notification_channel_attempts")).isEqualTo(1);
        }
    }

    @Test
    void concurrentReplaysConvergeAfterProducerTransactionsCreateSnapshots() throws Exception {
        CyclicBarrier snapshotsCreated = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<NotificationTaskReceipt> call = () -> transactions.execute(status -> {
                assertThat(count("notification_tasks")).isZero();
                await(snapshotsCreated);
                return recorder.record(event("correlation-snapshot", "CONFIRMED"));
            });
            List<NotificationTaskReceipt> receipts = executor.invokeAll(List.of(call, call)).stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(receipts).extracting(NotificationTaskReceipt::notificationId)
                    .containsOnly(receipts.getFirst().notificationId());
            assertThat(receipts).extracting(NotificationTaskReceipt::duplicate)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(count("notification_tasks")).isEqualTo(1);
            assertThat(count("notification_channel_attempts")).isEqualTo(1);
        }
    }

    private NotificationTaskReceipt record(NotificationSourceEventV1 event) {
        return transactions.execute(status -> recorder.record(event));
    }

    private NotificationSourceEventV1 event(String correlationId, String sourceState) {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-12T10:02:03+09:00");
        return new NotificationSourceEventV1(
                "pickup-confirmed-1",
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                "11",
                7L,
                NotificationResourceType.PICKUP_RESERVATION,
                "21",
                3L,
                sourceState,
                occurredAt,
                occurredAt,
                null,
                null,
                correlationId
        );
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("concurrent snapshot barrier failed", exception);
        }
    }
}
