package com.miriyum.domain.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.domain.reservation.service.ReservationHoldExpirationJob;
import com.miriyum.domain.schedule.closure.service.RegularClosureActivationJob;
import com.miriyum.domain.schedule.service.StoreScheduleActivationJob;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.menu.schedule.enabled=false",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.waiting.compensation.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        })
class NotificationSseHighWatermarkIT {

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
    @Autowired NotificationTaskRepository repository;
    @MockitoBean RegularClosureActivationJob regularClosureActivationJob;
    @MockitoBean StoreScheduleActivationJob storeScheduleActivationJob;
    @MockitoBean ReservationHoldExpirationJob reservationHoldExpirationJob;

    @Test
    void onlyFullyDeliveredPublicInAppHistoryAdvancesAccountWatermark() {
        insertConsumer(41L, "sse-owner@example.com");
        insertConsumer(42L, "sse-other@example.com");
        Instant occurredAt = Instant.parse("2026-08-19T01:00:00Z");

        insertTask(101L, 41L, "PENDING", true, null, occurredAt);
        insertAttempt(101L, "PENDING");
        insertTask(102L, 41L, "FAILED", true, null, occurredAt);
        insertAttempt(102L, "FAILED");
        insertTask(103L, 41L, "CANCELLED", true, null, occurredAt);
        insertAttempt(103L, "CANCELLED");
        insertTask(104L, 41L, "DELIVERED", true, occurredAt.plusSeconds(1), occurredAt);
        insertTask(105L, 41L, "PENDING", true, null, occurredAt);
        insertAttempt(105L, "DELIVERED");
        insertTask(106L, 41L, "DELIVERED", false, occurredAt.plusSeconds(1), occurredAt);
        insertAttempt(106L, "DELIVERED");
        insertTask(107L, 41L, "DELIVERED", true, null, occurredAt);
        insertAttempt(107L, "DELIVERED");
        insertTask(109L, 41L, "DELIVERED", true, occurredAt.plusSeconds(1), occurredAt);
        insertAttempt(109L, "DELIVERED");
        insertTask(110L, 42L, "DELIVERED", true, occurredAt.plusSeconds(1), occurredAt);
        insertAttempt(110L, "DELIVERED");
        insertTask(111L, 41L, "DELIVERED", true, occurredAt.plusSeconds(1), occurredAt);
        insertAttempt(111L, "FAILED");

        assertThat(repository.findDeliveredInAppHighWatermark(41L)).isEqualTo(109L);
        assertThat(repository.findDeliveredInAppHighWatermark(42L)).isEqualTo(110L);
        assertThat(repository.findDeliveredInAppHighWatermark(99L)).isZero();
    }

    private void insertConsumer(long accountId, String email) {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status,
                    created_at, updated_at
                ) VALUES (?, ?, 'hash', 'SSE소유자', 'ACTIVE', NOW(6), NOW(6))
                """, accountId, email);
    }

    private void insertTask(
            long notificationId,
            long recipientAccountId,
            String status,
            boolean hasTitle,
            Instant deliveredAt,
            Instant occurredAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO notification_tasks (
                    notification_id, source_domain, source_event_id, purpose,
                    recipient_account_id, recipient_relation_version,
                    resource_type, resource_id, resource_version, source_state,
                    occurred_at, scheduled_at, correlation_id, contract_version,
                    payload_fingerprint, status, title, delivered_at
                ) VALUES (?, 'PICKUP', ?, 'PICKUP_RESERVATION_CONFIRMED',
                          ?, 1, 'PICKUP_RESERVATION', 31, 1, 'CONFIRMED',
                          ?, ?, ?, 'notification-source-event-v1', ?, ?, ?, ?)
                """,
                notificationId,
                "sse-event-" + notificationId,
                recipientAccountId,
                utc(occurredAt),
                utc(occurredAt),
                "sse-correlation-" + notificationId,
                String.format("%064d", notificationId),
                status,
                hasTitle ? "픽업 예약이 확정되었습니다." : null,
                deliveredAt == null ? null : utc(deliveredAt));
    }

    private void insertAttempt(long notificationId, String status) {
        jdbcTemplate.update("""
                INSERT INTO notification_channel_attempts (
                    notification_id, channel, status, attempt_count, last_attempted_at
                ) VALUES (?, 'IN_APP', ?, 1, NOW(6))
                """, notificationId, status);
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
