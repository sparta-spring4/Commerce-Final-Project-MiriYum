package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.dto.WaitingAnalyticsSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
@SpringBootTest(classes = MiriyumApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.store.schedule.activation-delay-ms=600000",
        "miriyum.reservation.time-policy.activation-enabled=false",
        "miriyum.reservation.hold-expiration.enabled=false",
        "miriyum.waiting.closure.initial-delay-ms=600000"
})
class WaitingAnalyticsQueryServiceIT {

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

    @Autowired WaitingAnalyticsQueryService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixture() {
        jdbc.execute("DELETE FROM waiting_status_events");
        jdbc.execute("DELETE FROM waiting_teams");
        jdbc.execute("DELETE FROM stores");
        jdbc.execute("DELETE FROM store_operator_accounts");
        jdbc.execute("DELETE FROM consumer_accounts");
        insertParents();

        jdbc.update("""
                INSERT INTO waiting_teams (
                    waiting_team_id, store_id, consumer_account_id, business_date,
                    party_size, source, queue_sequence, status, version, created_at,
                    called_at, arrival_deadline
                ) VALUES (1, 17, 41, '2026-08-16', 3, 'REMOTE', 1, 'CALLED', 1,
                    '2026-08-16 08:30:00', '2026-08-16 09:05:00', '2026-08-16 09:15:00')
                """);
        event(1, 1, "WAITING", "2026-08-16 08:30:00");
        event(1, 2, "CALLED", "2026-08-16 09:05:00");

        jdbc.update("""
                INSERT INTO waiting_teams (
                    waiting_team_id, store_id, consumer_account_id, business_date,
                    party_size, source, queue_sequence, status, version, created_at,
                    called_at, arrival_deadline, no_show_at
                ) VALUES (2, 17, 41, '2026-08-16', 2, 'REMOTE', 2, 'NO_SHOW', 2,
                    '2026-08-16 07:00:00', '2026-08-16 07:10:00',
                    '2026-08-16 07:20:00', '2026-08-16 07:21:00')
                """);
        event(2, 1, "WAITING", "2026-08-16 07:00:00");
        event(2, 2, "CALLED", "2026-08-16 07:10:00");
        event(2, 3, "NO_SHOW", "2026-08-16 07:21:00");

        jdbc.update("""
                INSERT INTO waiting_teams (
                    waiting_team_id, store_id, consumer_account_id, business_date,
                    party_size, source, queue_sequence, status, version, created_at,
                    called_at, arrival_deadline, arrived_at
                ) VALUES (3, 17, 41, '2026-08-16', 4, 'ON_SITE', 3, 'ARRIVED', 2,
                    '2026-08-16 08:00:00', '2026-08-16 08:10:00',
                    '2026-08-16 08:20:00', '2026-08-16 08:15:00')
                """);
        event(3, 1, "WAITING", "2026-08-16 08:00:00");
        event(3, 2, "CALLED", "2026-08-16 08:10:00");
        event(3, 3, "ARRIVED", "2026-08-16 08:15:00");
    }

    @Test
    void laterStatusEventsDoNotChangeAnEarlierAsOfSnapshot() {
        WaitingAnalyticsSnapshot snapshot = service.getDashboardSnapshot(
                STORE_ID, BUSINESS_DATE, AS_OF);

        assertThat(snapshot.waitingTeams()).isEqualTo(1L);
        assertThat(snapshot.calledTeams()).isZero();
        assertThat(snapshot.waitingPeople()).isEqualTo(3L);
        assertThat(snapshot.calledPeople()).isZero();
        assertThat(snapshot.longestWaitSeconds()).isEqualTo(1800L);
        assertThat(snapshot.confirmedNoShowTeams()).isEqualTo(1L);
        assertThat(snapshot.dataThrough()).isEqualTo(Instant.parse("2026-08-16T08:30:00Z"));
    }

    private void insertParents() {
        jdbc.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status,
                    created_at, updated_at
                ) VALUES (41, 'waiting-analytics-consumer@example.com', 'hash',
                    '웨이팅 분석자', 'ACTIVE', NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name, status,
                    created_at, updated_at
                ) VALUES (31, 'waiting-analytics-owner@example.com', 'hash',
                    '웨이팅 운영자', 'ACTIVE', NOW(6), NOW(6))
                """);
        jdbc.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    business_type, name, description, region, address, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, store_category_code, verification_status,
                    operation_status, reservation_enabled, menu_hold_enabled, pickup_enabled,
                    created_at, updated_at
                ) VALUES (17, 31, '2700000018', 'CAFE', '웨이팅 분석 매장', '', 'SEOUL',
                    '서울시 중구', 'Asia/Seoul', NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY', 'APPROVED',
                    'OPEN', TRUE, TRUE, TRUE, NOW(6), NOW(6))
                """);
    }

    private void event(long teamId, long sequence, String status, String occurredAt) {
        jdbc.update("""
                INSERT INTO waiting_status_events (
                    waiting_team_id, event_sequence, public_status,
                    occurred_at, publication_state
                ) VALUES (?, ?, ?, ?, 'PUBLISHED')
                """, teamId, sequence, status, occurredAt);
    }
}
