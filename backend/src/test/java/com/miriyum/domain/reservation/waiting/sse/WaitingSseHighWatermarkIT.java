package com.miriyum.domain.reservation.waiting.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.service.ReservationHoldExpirationJob;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.schedule.closure.service.RegularClosureActivationJob;
import com.miriyum.domain.schedule.service.StoreScheduleActivationJob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-b")
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
class WaitingSseHighWatermarkIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 19);

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
    @Autowired WaitingStatusEventRepository repository;
    @MockitoBean RegularClosureActivationJob regularClosureActivationJob;
    @MockitoBean StoreScheduleActivationJob storeScheduleActivationJob;
    @MockitoBean ReservationHoldExpirationJob reservationHoldExpirationJob;

    @Test
    void mysqlScopesActiveInactiveAndStoreWatermarksWithoutCrossAudienceLeakage() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            setForeignKeyChecks(connection, false);
            try {
                insertTeam(connection, 101L, 10L, 1L, BUSINESS_DATE, 1L);
                insertTeam(connection, 102L, 10L, 2L, BUSINESS_DATE, 2L);
                insertTeam(connection, 103L, 10L, 41L, BUSINESS_DATE, 3L);
                insertTeam(connection, 104L, 10L, 4L, BUSINESS_DATE, 4L);
                insertTeam(connection, 105L, 10L, 5L, BUSINESS_DATE.plusDays(1), 1L);
                insertTeam(connection, 106L, 11L, 6L, BUSINESS_DATE, 1L);
                insertTeam(connection, 201L, 12L, 42L, BUSINESS_DATE, 1L);
                insertTeam(connection, 202L, 12L, 42L, BUSINESS_DATE.plusDays(1), 1L);

                insertEvent(connection, 11L, 101L);
                insertEvent(connection, 22L, 102L);
                insertEvent(connection, 33L, 103L);
                insertEvent(connection, 44L, 104L);
                insertEvent(connection, 55L, 105L);
                insertEvent(connection, 66L, 106L);
                insertEvent(connection, 70L, 201L);
                insertEvent(connection, 77L, 202L);
                insertMembership(connection, 10L, 41L, 103L);
                return null;
            } finally {
                setForeignKeyChecks(connection, true);
            }
        });

        var active = repository.findActiveConsumerSseHighWatermark(41L).orElseThrow();
        assertThat(active.getWatermark()).isEqualTo(33L);
        assertThat(active.getStoreId()).isEqualTo(10L);
        assertThat(active.getBusinessDate()).isEqualTo(BUSINESS_DATE);
        assertThat(repository.findLatestOwnedConsumerSseHighWatermark(42L)).isEqualTo(77L);
        assertThat(repository.findLatestOwnedConsumerSseHighWatermark(43L)).isZero();
        assertThat(repository.findStoreSseHighWatermark(10L)).isEqualTo(55L);
        assertThat(repository.findStoreSseHighWatermark(11L)).isEqualTo(66L);
        assertThat(repository.findStoreSseHighWatermark(99L)).isZero();
    }

    private static void insertTeam(
            Connection connection,
            long teamId,
            long storeId,
            long consumerAccountId,
            LocalDate businessDate,
            long queueSequence
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_teams (
                    waiting_team_id, store_id, consumer_account_id, business_date,
                    party_size, source, queue_sequence, status, version, created_at
                ) VALUES (?, ?, ?, ?, 2, 'REMOTE', ?, 'WAITING', 0, NOW(6))
                """)) {
            statement.setLong(1, teamId);
            statement.setLong(2, storeId);
            statement.setLong(3, consumerAccountId);
            statement.setObject(4, businessDate);
            statement.setLong(5, queueSequence);
            statement.executeUpdate();
        }
    }

    private static void insertEvent(Connection connection, long eventId, long teamId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_status_events (
                    waiting_status_event_id, waiting_team_id, event_sequence,
                    public_status, occurred_at, publication_state
                ) VALUES (?, ?, 1, 'WAITING', NOW(6), 'PENDING')
                """)) {
            statement.setLong(1, eventId);
            statement.setLong(2, teamId);
            statement.executeUpdate();
        }
    }

    private static void insertMembership(
            Connection connection,
            long storeId,
            long consumerAccountId,
            long teamId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_active_memberships (
                    store_id, consumer_account_id, waiting_team_id, created_at
                ) VALUES (?, ?, ?, NOW(6))
                """)) {
            statement.setLong(1, storeId);
            statement.setLong(2, consumerAccountId);
            statement.setLong(3, teamId);
            statement.executeUpdate();
        }
    }

    private static void setForeignKeyChecks(Connection connection, boolean enabled)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = " + (enabled ? "1" : "0"));
        }
    }
}
