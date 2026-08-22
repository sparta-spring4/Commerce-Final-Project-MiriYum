package com.miriyum.domain.pickup.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
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
@Tag("integration-shard-d")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        }
)
class PickupNotificationRollbackIT {

    private static final Instant CANCELLED_AT = Instant.parse("2026-08-09T02:00:00Z");

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired NotificationTaskRecorder recorder;
    @Autowired PickupNotificationPublisher publisher;
    @Autowired PickupReservationRepository pickupRepository;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM notification_task_transition_audits");
        jdbcTemplate.execute("DELETE FROM notification_channel_attempts");
        jdbcTemplate.execute("DELETE FROM notification_tasks");
        jdbcTemplate.execute("DELETE FROM pickup_reservation_items");
        jdbcTemplate.execute("DELETE FROM pickup_reservations");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        insertOwnerAndStore();
        jdbcTemplate.update("""
                INSERT INTO pickup_reservations (
                    pickup_reservation_id, consumer_account_id, store_id,
                    store_name_snapshot, time_zone_id_snapshot,
                    pickup_date, pickup_time, pickup_at, acquire_operation_id,
                    status, created_at
                ) VALUES (
                    77, 11, 22,
                    '미리윰 식당', 'Asia/Seoul',
                    '2026-08-10', '12:00:00', '2026-08-10 03:00:00',
                    'pickup-acquire-rollback-test',
                    'CONFIRMED', '2026-08-09 01:00:00'
                )
                """);
        transactions.executeWithoutResult(ignored -> recorder.record(conflictingEvent()));
    }

    @Test
    void sourceEventConflictRollsBackPickupStateAndKeepsFirstTask() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            PickupReservation pickup = pickupRepository.findById(77L).orElseThrow();
            pickup.cancelByConsumer(null, CANCELLED_AT);
            publisher.recordCancelled(pickup, CANCELLED_AT, "pickup-cancel-request");
        })).isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(NotificationErrorCode.SOURCE_EVENT_CONFLICT));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM pickup_reservations WHERE pickup_reservation_id = 77",
                String.class
        )).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_tasks",
                Integer.class
        )).isEqualTo(1);
    }

    private NotificationSourceEventV1 conflictingEvent() {
        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(
                CANCELLED_AT.minusSeconds(60), ZoneOffset.UTC
        );
        return new NotificationSourceEventV1(
                "pickup-reservation:77:cancelled",
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CANCELLED,
                "11",
                1L,
                NotificationResourceType.PICKUP_RESERVATION,
                "77",
                2L,
                "CANCELLED",
                occurredAt,
                occurredAt,
                null,
                null,
                "first-cancellation-request"
        );
    }

    private void insertOwnerAndStore() {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status,
                    created_at, updated_at
                ) VALUES (
                    11, 'pickup-notification@example.com', 'hash', '픽업소유자', 'ACTIVE',
                    NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (
                    31, 'pickup-store@example.com', 'hash', '픽업매장', 'ACTIVE',
                    NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    name, description, region, address, store_category_code,
                    time_zone_id,
                    verification_status, operation_status,
                    reservation_enabled, menu_hold_enabled, pickup_enabled,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version,
                    created_at, updated_at
                ) VALUES (
                    22, 31, '1234567890',
                    '미리윰 식당', '테스트 매장', 'SEOUL', '서울시 테스트로 1',
                    'CAFE_BAKERY', 'Asia/Seoul', 'APPROVED', 'OPEN',
                    TRUE, TRUE, TRUE, NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', NOW(6), NOW(6)
                )
                """);
    }
}
