package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository.HistoryBoundary;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.notification.history.cursor-secret=history-secret-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-delay-ms=3600000",
            "miriyum.menu.schedule.initial-delay-ms=3600000",
            "miriyum.reservation.time-policy.activation-delay-ms=3600000"
        }
)
class NotificationHistoryIntegrationTest {

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

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired NotificationTaskRepository repository;
    @Autowired NotificationTaskRecorder recorder;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM notification_task_transition_audits");
        jdbcTemplate.execute("DELETE FROM notification_channel_attempts");
        jdbcTemplate.execute("DELETE FROM notification_tasks");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        insertConsumer(11L, "history-owner@example.com");
        insertConsumer(12L, "other-owner@example.com");
    }

    @Test
    void exposesOnlyDeliveredTaskWithDeliveredInAppAttemptForSameConsumer() {
        Instant occurredAt = Instant.parse("2026-08-13T01:02:03Z");
        insertTask(105L, 11L, "DELIVERED", occurredAt, occurredAt.plusSeconds(1));
        insertAttempt(105L, "DELIVERED");
        insertTask(104L, 11L, "DELIVERED", occurredAt, occurredAt.plusSeconds(1));
        insertAttempt(104L, "FAILED");
        insertTask(103L, 11L, "PENDING", occurredAt, null);
        insertAttempt(103L, "DELIVERED");
        insertTask(102L, 11L, "DELIVERED", occurredAt, null);
        insertAttempt(102L, "DELIVERED");
        insertTask(100L, 11L, "DELIVERED", occurredAt, occurredAt.plusSeconds(1));
        jdbcTemplate.update(
                "UPDATE notification_tasks SET title = NULL WHERE notification_id = 100");
        insertAttempt(100L, "DELIVERED");
        insertTask(101L, 12L, "DELIVERED", occurredAt, occurredAt.plusSeconds(1));
        insertAttempt(101L, "DELIVERED");

        assertThat(repository.findDeliveredInAppHistory(11L, null, 10))
                .extracting(task -> task.notificationId())
                .containsExactly(105L);
    }

    @Test
    void appliesOccurredAtAndNotificationIdKeysetBoundaryWithoutDependingOnRowExistence() {
        Instant tied = Instant.parse("2026-08-13T01:02:03Z");
        insertDelivered(103L, tied);
        insertDelivered(102L, tied);
        insertDelivered(101L, tied.minusSeconds(1));

        assertThat(repository.findDeliveredInAppHistory(
                11L,
                new HistoryBoundary(tied, 103L),
                10
        )).extracting(task -> task.notificationId())
                .containsExactly(102L, 101L);

        assertThat(repository.findDeliveredInAppHistory(
                11L,
                new HistoryBoundary(tied, 999L),
                10
        )).extracting(task -> task.notificationId())
                .containsExactly(103L, 102L, 101L);
    }

    @Test
    void recordedHistoryRoundTripsUtcAndKeepsCursorBoundaryInAsiaSeoulSession() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-17T12:00:00+09:00");
        Instant beforeRecord = databaseUtcNow();
        long firstId = recordDelivered("history-record-1", occurredAt);
        long secondId = recordDelivered("history-record-2", occurredAt);
        Instant afterRecord = databaseUtcNow();

        var firstPage = repository.findDeliveredInAppHistory(11L, null, 1);

        assertThat(firstPage).singleElement().satisfies(task -> {
            assertThat(task.notificationId()).isEqualTo(secondId);
            assertThat(task.occurredAt()).isEqualTo(Instant.parse("2026-08-17T03:00:00Z"));
            assertThat(task.createdAt()).isBetween(beforeRecord, afterRecord);
            assertThat(task.deliveredAt()).isEqualTo(Instant.parse("2026-08-17T03:00:01Z"));
        });

        var boundary = new HistoryBoundary(
                firstPage.getFirst().occurredAt(),
                firstPage.getFirst().notificationId()
        );
        assertThat(repository.findDeliveredInAppHistory(11L, boundary, 1))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.notificationId()).isEqualTo(firstId);
                    assertThat(task.occurredAt())
                            .isEqualTo(Instant.parse("2026-08-17T03:00:00Z"));
                });
    }

    private Instant databaseUtcNow() {
        LocalDateTime value = jdbcTemplate.queryForObject(
                "SELECT UTC_TIMESTAMP(6)",
                LocalDateTime.class
        );
        return value.toInstant(ZoneOffset.UTC);
    }

    private void insertDelivered(long notificationId, Instant occurredAt) {
        insertTask(notificationId, 11L, "DELIVERED", occurredAt, occurredAt.plusSeconds(1));
        insertAttempt(notificationId, "DELIVERED");
    }

    private void insertConsumer(long accountId, String email) {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status, created_at, updated_at
                ) VALUES (?, ?, 'hash', '알림소유자', 'ACTIVE', NOW(6), NOW(6))
                """, accountId, email);
    }

    private void insertTask(
            long notificationId,
            long recipientAccountId,
            String status,
            Instant occurredAt,
            Instant deliveredAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO notification_tasks (
                    notification_id, source_domain, source_event_id, purpose,
                    recipient_account_id, recipient_relation_version,
                    resource_type, resource_id, resource_version, source_state,
                    occurred_at, scheduled_at, correlation_id, contract_version,
                    payload_fingerprint, status, title, delivered_at
                ) VALUES (?, 'PICKUP', ?, 'PICKUP_RESERVATION_CONFIRMED',
                          ?, 7, 'PICKUP_RESERVATION', 31, 3, 'CONFIRMED',
                          FROM_UNIXTIME(?), FROM_UNIXTIME(?),
                          ?, 'notification-source-event-v1',
                          ?, ?, '픽업 예약이 확정되었습니다.', FROM_UNIXTIME(?))
                """,
                notificationId,
                "history-event-" + notificationId,
                recipientAccountId,
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                "history-correlation-" + notificationId,
                String.format("%064d", notificationId),
                status,
                deliveredAt == null
                        ? null
                        : LocalDateTime.ofInstant(deliveredAt, ZoneOffset.UTC)
        );
    }

    private long recordDelivered(String sourceEventId, OffsetDateTime occurredAt) {
        NotificationTaskReceipt receipt = transactions.execute(status -> recorder.record(
                new NotificationSourceEventV1(
                        sourceEventId,
                        NotificationSourceDomain.PICKUP,
                        NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                        "11",
                        7L,
                        NotificationResourceType.PICKUP_RESERVATION,
                        "31",
                        3L,
                        "CONFIRMED",
                        occurredAt,
                        occurredAt,
                        null,
                        null,
                        "history-correlation-" + sourceEventId
                )
        ));
        LocalDateTime deliveredAt = LocalDateTime.ofInstant(
                occurredAt.toInstant().plusSeconds(1),
                ZoneOffset.UTC
        );
        jdbcTemplate.update("""
                UPDATE notification_tasks
                   SET status = 'DELIVERED',
                       title = '픽업 예약이 확정되었습니다.',
                       delivered_at = ?
                 WHERE notification_id = ?
                """, deliveredAt, receipt.notificationId());
        jdbcTemplate.update("""
                UPDATE notification_channel_attempts
                   SET status = 'DELIVERED', last_attempted_at = ?
                 WHERE notification_id = ? AND channel = 'IN_APP'
                """, deliveredAt, receipt.notificationId());
        return Long.parseLong(receipt.notificationId());
    }

    private void insertAttempt(long notificationId, String status) {
        jdbcTemplate.update("""
                INSERT INTO notification_channel_attempts (
                    notification_id, channel, status, attempt_count, last_attempted_at
                ) VALUES (?, 'IN_APP', ?, 1, NOW(6))
                """, notificationId, status);
    }
}
