package com.miriyum.domain.reservation.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.repository.ReservationRepository;
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
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        }
)
class ReservationNotificationRollbackIT {

    private static final Instant CANCELLED_AT = Instant.parse("2026-08-01T10:01:00Z");

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
    @Autowired ReservationNotificationPublisher publisher;
    @Autowired ReservationRepository reservationRepository;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM notification_task_transition_audits");
        jdbcTemplate.execute("DELETE FROM notification_channel_attempts");
        jdbcTemplate.execute("DELETE FROM notification_tasks");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        insertOwnerAndStore();
        jdbcTemplate.update("""
                INSERT INTO reservations (
                    reservation_id, consumer_account_id, store_id, store_name_snapshot,
                    service_date, start_time, end_time,
                    adult_count, child_count, infant_count,
                    notification_target_reference, contact_available_at_confirmation,
                    capacity_policy_version, reservation_policy_version,
                    cancellation_policy_version, status, created_at
                ) VALUES (
                    77, 11, 22, '미리윰 식당',
                    '2026-08-10', '12:00:00', '13:00:00',
                    2, 0, 0,
                    'consumer:11:channel:primary', TRUE,
                    1, 1, 1, 'CONFIRMED', '2026-08-01 09:00:00'
                )
                """);
        transactions.executeWithoutResult(ignored -> recorder.record(conflictingEvent()));
    }

    @Test
    void sourceEventConflictRollsBackReservationStateAndKeepsFirstTask() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            Reservation reservation = reservationRepository.findById(77L).orElseThrow();
            reservation.cancel(CANCELLED_AT);
            publisher.recordCancelled(reservation, CANCELLED_AT, "reservation-cancel-request");
        })).isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(NotificationErrorCode.SOURCE_EVENT_CONFLICT));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE reservation_id = 77",
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
                "reservation:77:cancelled",
                NotificationSourceDomain.RESERVATION,
                NotificationPurpose.RESERVATION_CANCELLED,
                "11",
                1L,
                NotificationResourceType.RESERVATION,
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
                    11, 'reservation-notification@example.com', 'hash', '예약소유자', 'ACTIVE',
                    NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (
                    31, 'reservation-store@example.com', 'hash', '예약매장', 'ACTIVE',
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
