package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.domain.notification.config.NotificationSettings.RuntimePolicy;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.port.PickupNotificationSource;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.sse.SseWakeUpBroker;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
            "miriyum.store.schedule.activation-delay-ms=3600000",
            "miriyum.menu.schedule.initial-delay-ms=3600000",
            "miriyum.reservation.time-policy.activation-delay-ms=3600000"
        })
class NotificationReadIntegrationTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired NotificationReadService service;
    @Autowired NotificationDeliveryService deliveryService;
    @Autowired NotificationTaskRecorder recorder;
    @Autowired TransactionTemplate transactions;
    @MockitoBean SseWakeUpBroker wakeUpBroker;
    @MockitoBean PickupNotificationSource pickupSource;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM notification_consumer_change_states");
        jdbcTemplate.execute("DELETE FROM notification_task_transition_audits");
        jdbcTemplate.execute("DELETE FROM notification_channel_attempts");
        jdbcTemplate.execute("DELETE FROM notification_tasks");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        insertConsumer(11L, "read-owner@example.com");
        insertConsumer(12L, "read-other@example.com");
    }

    @Test
    void singleReadChangesOnlyTheOwnersPublicDeliveredNotificationOnce() {
        insertPublicUnread(101L, 11L);
        insertPublicUnread(102L, 11L);
        insertPublicUnread(103L, 12L);

        assertThat(service.readOne(11L, 101L).unreadCount()).isEqualTo(1L);
        long firstVersion = changeVersion(11L);
        LocalDateTime firstReadAt = readAt(101L);

        assertThat(service.readOne(11L, 101L).unreadCount()).isEqualTo(1L);
        assertThat(changeVersion(11L)).isEqualTo(firstVersion);
        assertThat(readAt(101L)).isEqualTo(firstReadAt);
        assertThat(readAt(102L)).isNull();
        assertThat(readAt(103L)).isNull();
    }

    @Test
    void allReadUsesTheWholePublicSetAndAdvancesOnlyForAnActualChange() {
        insertPublicUnread(101L, 11L);
        insertPublicUnread(102L, 11L);

        assertThat(service.readAll(11L).unreadCount()).isZero();
        long firstVersion = changeVersion(11L);

        assertThat(service.readAll(11L).unreadCount()).isZero();
        assertThat(changeVersion(11L)).isEqualTo(firstVersion);
        assertThat(readAt(101L)).isEqualTo(readAt(102L));
    }

    @Test
    void anotherAccountsNotificationAndMissingIdUseTheSameError() {
        insertPublicUnread(103L, 12L);

        assertNotFound(11L, 103L);
        assertNotFound(11L, 999L);
    }

    @Test
    void parallelSingleReadChangesTheRowAndVersionOnlyOnce() throws Exception {
        insertPublicUnread(101L, 11L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Long> first = executor.submit(() -> readAfterBarrier(ready, start));
            Future<Long> second = executor.submit(() -> readAfterBarrier(ready, start));
            ready.await();
            start.countDown();

            assertThat(first.get()).isZero();
            assertThat(second.get()).isZero();
        }

        assertThat(changeVersion(11L)).isEqualTo(1L);
        assertThat(readAt(101L)).isNotNull();
    }

    @Test
    void rollbackKeepsReadStateVersionAndWakeUpUnchanged() {
        insertPublicUnread(101L, 11L);

        transactions.executeWithoutResult(status -> {
            assertThat(service.readOne(11L, 101L).unreadCount()).isZero();
            status.setRollbackOnly();
        });

        assertThat(service.getUnreadCount(11L).unreadCount()).isOne();
        assertThat(readAt(101L)).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification_consumer_change_states
                 WHERE consumer_account_id = 11
                """, Long.class)).isZero();
        verifyNoInteractions(wakeUpBroker);
    }

    @Test
    void deliveryCommittedAfterAllReadIsTheOnlyUnreadNotification() throws Exception {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-21T00:00:00Z");
        transactions.executeWithoutResult(status -> recorder.record(new NotificationSourceEventV1(
                "read-delivery-race", NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED, "11", 1L,
                NotificationResourceType.PICKUP_RESERVATION, "31", 1L, "CONFIRMED",
                occurredAt, occurredAt, null, null, "read-delivery-race")));
        CountDownLatch sourceRead = new CountDownLatch(1);
        CountDownLatch continueDelivery = new CountDownLatch(1);
        given(pickupSource.readContext(anyString(), anyLong(), anyString()))
                .willAnswer(invocation -> {
                    sourceRead.countDown();
                    continueDelivery.await();
                    return new NotificationSourceContextV1(
                            NotificationSourceReadResult.FOUND, 1L, 1L, "CONFIRMED",
                            "미리윰", null, null, null, null, null, null, null);
                });
        RuntimePolicy policy = new RuntimePolicy(
                "v1", "read-test-worker", 1, Duration.ofSeconds(30), 3,
                Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> delivery = executor.submit(() -> deliveryService.deliverDueBatch(policy));
            sourceRead.await();
            assertThat(service.readAll(11L).unreadCount()).isZero();
            continueDelivery.countDown();
            assertThat(delivery.get()).isOne();
        }

        assertThat(service.getUnreadCount(11L).unreadCount()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT read_at FROM notification_tasks WHERE source_event_id = 'read-delivery-race'
                """, LocalDateTime.class)).isNull();
    }

    private long readAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return service.readOne(11L, 101L).unreadCount();
    }

    private void assertNotFound(long accountId, long notificationId) {
        assertThatThrownBy(() -> service.readOne(accountId, notificationId))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    private void insertConsumer(long accountId, String email) {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status, created_at, updated_at
                ) VALUES (?, ?, 'hash', '알림소유자', 'ACTIVE', NOW(6), NOW(6))
                """, accountId, email);
    }

    private void insertPublicUnread(long notificationId, long accountId) {
        jdbcTemplate.update("""
                INSERT INTO notification_tasks (
                    notification_id, source_domain, source_event_id, purpose,
                    recipient_account_id, recipient_relation_version,
                    resource_type, resource_id, resource_version, source_state,
                    occurred_at, scheduled_at, correlation_id, contract_version,
                    payload_fingerprint, status, title, delivered_at, read_at
                ) VALUES (?, 'PICKUP', ?, 'PICKUP_RESERVATION_CONFIRMED',
                          ?, 1, 'PICKUP_RESERVATION', 31, 1, 'CONFIRMED',
                          NOW(6), NOW(6), ?, 'notification-source-event-v1',
                          ?, 'DELIVERED', '픽업 예약이 확정되었습니다.', NOW(6), NULL)
                """, notificationId, "read-event-" + notificationId, accountId,
                "read-correlation-" + notificationId, String.format("%064d", notificationId));
        jdbcTemplate.update("""
                INSERT INTO notification_channel_attempts (
                    notification_id, channel, status, attempt_count, last_attempted_at
                ) VALUES (?, 'IN_APP', 'DELIVERED', 1, NOW(6))
                """, notificationId);
    }

    private long changeVersion(long accountId) {
        return jdbcTemplate.queryForObject("""
                SELECT change_version FROM notification_consumer_change_states
                 WHERE consumer_account_id = ?
                """, Long.class, accountId);
    }

    private LocalDateTime readAt(long notificationId) {
        return jdbcTemplate.queryForObject(
                "SELECT read_at FROM notification_tasks WHERE notification_id = ?",
                LocalDateTime.class,
                notificationId);
    }
}
