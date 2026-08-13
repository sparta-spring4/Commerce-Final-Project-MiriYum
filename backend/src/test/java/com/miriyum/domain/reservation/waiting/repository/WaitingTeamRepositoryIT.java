package com.miriyum.domain.reservation.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
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
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        })
@Transactional
class WaitingTeamRepositoryIT {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private WaitingTeamRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("repository fixture 삽입이 끝나면 같은 MySQL 연결의 FK 검사를 복원한다")
    void restoresForeignKeyChecksAfterFixtureInsertion() {
        insertFixtureWithForeignKeysDisabled(connection -> List.of());

        assertThat(foreignKeyChecks()).isEqualTo(1);
    }

    @Test
    @DisplayName("fixture 삽입 중 예외가 발생해도 같은 연결의 FK 검사를 복원하고 무결성을 강제한다")
    void restoresForeignKeyChecksAfterFixtureFailure() {
        assertThatThrownBy(() -> insertFixtureWithForeignKeysDisabled(connection -> {
            throw new IllegalStateException("fixture failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("fixture failure");

        assertThat(foreignKeyChecks()).isEqualTo(1);
        assertThatThrownBy(() -> session().doWork(connection ->
                insertTeam(connection, 22L, LocalDate.of(2026, 8, 12), 99L, "WAITING")))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("keyset 목록은 매장과 선택 상태를 제한하고 순번과 ID로 안정 정렬한다")
    void scopesByStoreAndStatusWithStableTieBreak() {
        List<Long> fixtureIds = insertFixtureWithForeignKeysDisabled(connection -> {
            long firstId = insertTeam(
                    connection, 22L, LocalDate.of(2026, 8, 12), 1L, "WAITING");
            long tiedLaterId = insertTeam(
                    connection, 22L, LocalDate.of(2026, 8, 13), 1L, "WAITING");
            long thirdId = insertTeam(
                    connection, 22L, LocalDate.of(2026, 8, 12), 2L, "CALLED");
            insertTeam(connection, 23L, LocalDate.of(2026, 8, 12), 1L, "WAITING");
            return List.of(firstId, tiedLaterId, thirdId);
        });
        entityManager.clear();

        List<WaitingTeam> all = repository.findKeysetPage(22L, null, null, null, 10);
        List<WaitingTeam> waiting = repository.findKeysetPage(
                22L, WaitingTeamStatus.WAITING, null, null, 10);

        assertThat(all).extracting(WaitingTeam::getId)
                .containsExactlyElementsOf(fixtureIds);
        assertThat(waiting).extracting(WaitingTeam::getId)
                .containsExactly(fixtureIds.get(0), fixtureIds.get(1));
        assertThat(foreignKeyChecks()).isEqualTo(1);
    }

    @Test
    @DisplayName("복합 cursor 페이지 경계는 중복이나 누락 없이 다음 행부터 이어진다")
    void continuesAfterCompositeCursorWithoutDuplicatesOrSkips() {
        List<Long> fixtureIds = insertFixtureWithForeignKeysDisabled(connection -> List.of(
                insertTeam(connection, 22L, LocalDate.of(2026, 8, 12), 1L, "WAITING"),
                insertTeam(connection, 22L, LocalDate.of(2026, 8, 13), 1L, "WAITING"),
                insertTeam(connection, 22L, LocalDate.of(2026, 8, 12), 2L, "WAITING")));
        entityManager.clear();

        List<WaitingTeam> pageOne = repository.findKeysetPage(22L, null, null, null, 2);
        WaitingTeam cursor = pageOne.getLast();
        List<WaitingTeam> pageTwo = repository.findKeysetPage(
                22L, null, cursor.getQueueSequence(), cursor.getId(), 2);

        assertThat(pageOne).extracting(WaitingTeam::getId)
                .containsExactly(fixtureIds.get(0), fixtureIds.get(1));
        assertThat(pageTwo).extracting(WaitingTeam::getId)
                .containsExactly(fixtureIds.get(2));
        assertThat(foreignKeyChecks()).isEqualTo(1);
    }

    private <T> T insertFixtureWithForeignKeysDisabled(SqlFixture<T> fixture) {
        return session().doReturningWork(connection -> {
            setForeignKeyChecks(connection, false);
            try {
                return fixture.insert(connection);
            } finally {
                setForeignKeyChecks(connection, true);
            }
        });
    }

    private int foreignKeyChecks() {
        return session().doReturningWork(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT @@SESSION.FOREIGN_KEY_CHECKS")) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        });
    }

    private long insertTeam(
            Connection connection,
            long storeId,
            LocalDate businessDate,
            long sequence,
            String status
    ) throws SQLException {
        long consumerId = 100_000L + storeId * 100L + sequence * 10L + businessDate.getDayOfMonth();
        Instant createdAt = Instant.parse("2026-08-12T03:00:00Z");
        Instant calledAt = "CALLED".equals(status) ? createdAt.plusSeconds(60) : null;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_teams (
                    store_id, consumer_account_id, business_date, party_size, source,
                    queue_sequence, status, version, created_at, called_at, arrival_deadline
                ) VALUES (?, ?, ?, 2, 'REMOTE', ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, storeId);
            statement.setLong(2, consumerId);
            statement.setObject(3, businessDate);
            statement.setLong(4, sequence);
            statement.setString(5, status);
            statement.setLong(6, calledAt == null ? 0L : 1L);
            statement.setTimestamp(7, Timestamp.from(createdAt));
            statement.setTimestamp(8, calledAt == null ? null : Timestamp.from(calledAt));
            statement.setTimestamp(9, calledAt == null
                    ? null : Timestamp.from(calledAt.plusSeconds(600)));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private void setForeignKeyChecks(Connection connection, boolean enabled) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = " + (enabled ? "1" : "0"));
        }
    }

    private Session session() {
        return entityManager.unwrap(Session.class);
    }

    @FunctionalInterface
    private interface SqlFixture<T> {
        T insert(Connection connection) throws SQLException;
    }
}
