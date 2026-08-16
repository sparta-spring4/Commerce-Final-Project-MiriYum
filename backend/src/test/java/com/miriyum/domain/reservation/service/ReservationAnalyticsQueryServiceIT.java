package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.contract.ReservationAnalyticsSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-a")
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.store.schedule.activation-enabled=false",
        "miriyum.reservation.time-policy.activation-enabled=false",
        "miriyum.waiting.closure.initial-delay-ms=600000"
})
class ReservationAnalyticsQueryServiceIT {

    private static final long STORE_ID = 17L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 16);
    private static final Instant AS_OF = Instant.parse("2026-08-16T09:00:00Z");

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired ReservationAnalyticsQueryService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixture() {
        resetFixture();
        insertParents();
        insertCapacityBuckets();
        insertReservation(1L, "CONFIRMED", "2026-08-16 08:00:00", null, null);
        insertReservation(2L, "CANCELLED", "2026-08-16 08:05:00",
                "2026-08-16 09:30:00", null);
        insertReservation(3L, "CANCELLED", "2026-08-16 08:10:00",
                "2026-08-16 08:30:00", null);
        insertReservation(4L, "FULFILLED", "2026-08-16 08:15:00", null,
                "2026-08-16 08:50:00");
        for (long reservationId = 1L; reservationId <= 4L; reservationId++) {
            jdbc.update("""
                    INSERT INTO reservation_capacity_allocations (
                        reservation_id, reservation_capacity_bucket_id,
                        occupied_people, occupied_teams, capacity_policy_version
                    ) VALUES (?, 101, 2, 1, 1)
                    """, reservationId);
        }
        insertCancellationAudit(3L, "2026-08-16 08:30:00", "cancel-3");
        insertCancellationAudit(2L, "2026-08-16 09:30:00", "cancel-2");
        jdbc.update("""
                INSERT INTO reservation_fulfillment_audits (
                    reservation_id, actor_type, actor_id, requested_at, occurred_at,
                    before_status, after_status, reservation_time_policy_version,
                    capacity_policy_version, command_id
                ) VALUES (4, 'STORE_OPERATOR', 31, '2026-08-16 08:49:00',
                    '2026-08-16 08:50:00', 'CONFIRMED', 'FULFILLED', 1, 1, 'fulfill-4')
                """);
    }

    private void resetFixture() {
        jdbc.execute("DELETE FROM reservation_fulfillment_audits");
        jdbc.execute("DELETE FROM reservation_cancellation_audits");
        jdbc.execute("DELETE FROM reservation_capacity_allocations");
        jdbc.execute("DELETE FROM reservations");
        jdbc.execute("DELETE FROM reservation_capacity_buckets");
        jdbc.execute("DELETE FROM stores");
        jdbc.execute("DELETE FROM store_operator_accounts");
        jdbc.execute("DELETE FROM consumer_accounts");
    }

    @Test
    void actualMySqlAggregatesTheEarlierAsOfWithoutLaterCapacityPolicy() {
        ReservationAnalyticsSnapshot snapshot = service.getDashboardSnapshot(
                STORE_ID, BUSINESS_DATE, AS_OF);

        assertThat(snapshot.todayReservationTeams()).isEqualTo(3L);
        assertThat(snapshot.cancelledTeams()).isEqualTo(1L);
        assertThat(snapshot.everConfirmedTeams()).isEqualTo(4L);
        assertThat(snapshot.reservedPeopleUnits()).isEqualTo(6L);
        assertThat(snapshot.reservedTeamUnits()).isEqualTo(3L);
        assertThat(snapshot.offeredPeopleUnits()).isEqualTo(8L);
        assertThat(snapshot.offeredTeamUnits()).isEqualTo(4L);
        assertThat(snapshot.dataThrough()).isEqualTo(Instant.parse("2026-08-16T08:50:00Z"));
    }

    @Test
    void laterAsOfSelectsThePolicyPublishedAfterTheEarlierBoundary() {
        ReservationAnalyticsSnapshot snapshot = service.getDashboardSnapshot(
                STORE_ID,
                BUSINESS_DATE,
                Instant.parse("2026-08-16T10:30:00Z"));

        assertThat(snapshot.offeredPeopleUnits()).isEqualTo(10L);
        assertThat(snapshot.offeredTeamUnits()).isEqualTo(5L);
    }

    @Test
    void duplicateAuditIsRejectedAndFullReaggregationIsStable() {
        ReservationAnalyticsSnapshot before = service.getDashboardSnapshot(
                STORE_ID, BUSINESS_DATE, AS_OF);

        assertThatThrownBy(() ->
                insertCancellationAudit(3L, "2026-08-16 08:30:00", "retry-cancel-3"))
                .isInstanceOf(DataIntegrityViolationException.class);

        ReservationAnalyticsSnapshot rebuilt = service.getDashboardSnapshot(
                STORE_ID, BUSINESS_DATE, AS_OF);
        assertThat(rebuilt).isEqualTo(before);
    }

    private void insertParents() {
        jdbc.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status,
                    created_at, updated_at
                ) VALUES (41, 'analytics-consumer@example.com', 'hash', '분석 예약자',
                    'ACTIVE', NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (31, 'analytics-owner@example.com', 'hash', '분석 운영자',
                    'ACTIVE', NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    business_type, name, description, region, address, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, store_category_code, verification_status,
                    operation_status, reservation_enabled, menu_hold_enabled, pickup_enabled,
                    created_at, updated_at
                ) VALUES (17, 31, '2700000017', 'CAFE', '분석 매장', '', 'SEOUL',
                    '서울시 중구', 'Asia/Seoul', NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY', 'APPROVED',
                    'OPEN', TRUE, TRUE, TRUE, NOW(6), NOW(6))
                """);
    }

    private void insertCapacityBuckets() {
        jdbc.update("""
                INSERT INTO reservation_capacity_buckets (
                    reservation_capacity_bucket_id, store_id, service_date,
                    start_time, end_time, max_people, max_teams, occupied_people,
                    occupied_teams, min_party_size, max_party_size, infants_allowed,
                    policy_version, policy_published_at
                ) VALUES (101, 17, '2026-08-16', '18:00:00', '19:00:00',
                    8, 4, 0, 0, 1, 8, TRUE, 1, '2026-08-16 08:00:00')
                """);
        jdbc.update("""
                INSERT INTO reservation_capacity_buckets (
                    reservation_capacity_bucket_id, store_id, service_date,
                    start_time, end_time, max_people, max_teams, occupied_people,
                    occupied_teams, min_party_size, max_party_size, infants_allowed,
                    policy_version, policy_published_at
                ) VALUES (102, 17, '2026-08-16', '18:00:00', '19:00:00',
                    10, 5, 0, 0, 1, 10, TRUE, 2, '2026-08-16 10:00:00')
                """);
    }

    private void insertReservation(
            long reservationId,
            String status,
            String createdAt,
            String cancelledAt,
            String fulfilledAt
    ) {
        jdbc.update("""
                INSERT INTO reservations (
                    reservation_id, consumer_account_id, store_id, store_name_snapshot,
                    service_date, start_time, end_time, adult_count, child_count,
                    infant_count, notification_target_reference,
                    contact_available_at_confirmation, capacity_policy_version,
                    reservation_policy_version, cancellation_policy_version, status,
                    created_at, cancelled_at, fulfilled_at
                ) VALUES (?, 41, 17, '분석 매장', '2026-08-16', '18:00:00',
                    '19:00:00', 2, 0, 0, 'opaque-contact', TRUE, 1, 1, 1, ?,
                    ?, ?, ?)
                """, reservationId, status, createdAt, cancelledAt, fulfilledAt);
    }

    private void insertCancellationAudit(
            long reservationId,
            String occurredAt,
            String commandId
    ) {
        jdbc.update("""
                INSERT INTO reservation_cancellation_audits (
                    reservation_id, actor_type, actor_id, cancellation_reason,
                    requested_at, occurred_at, before_status, after_status,
                    cancellation_policy_version, capacity_policy_version, command_id
                ) VALUES (?, 'CONSUMER', 41, NULL, ?, ?, 'CONFIRMED', 'CANCELLED',
                    1, 1, ?)
                """, reservationId, occurredAt, occurredAt, commandId);
    }
}
