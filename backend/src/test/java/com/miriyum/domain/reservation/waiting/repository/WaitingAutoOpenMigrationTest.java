package com.miriyum.domain.reservation.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-b")
class WaitingAutoOpenMigrationTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Test
    void appliesV50WithExactLedgerColumnsAndIndexes() throws Exception {
        Flyway flyway = migrate();

        assertThat(flyway.info().applied()).anyMatch(migration ->
                "50".equals(String.valueOf(migration.getVersion()))
                        && "V50__create_waiting_auto_open_runtime.sql"
                        .equals(migration.getScript()));

        try (Connection connection = connection()) {
            assertThat(columns(connection, "waiting_auto_open_jobs")).containsExactly(
                    "waiting_auto_open_job_id",
                    "store_id",
                    "business_interval_key",
                    "business_date",
                    "interval_starts_at",
                    "interval_ends_at",
                    "scheduled_at",
                    "expected_settings_version",
                    "expected_advance_open_minutes",
                    "idempotency_key",
                    "status",
                    "attempt_count",
                    "next_attempt_at",
                    "lease_owner",
                    "lease_until",
                    "fencing_token",
                    "failure_code",
                    "last_attempted_at",
                    "completed_at",
                    "created_at",
                    "updated_at");
            assertThat(columns(connection, "waiting_reception_windows")).containsExactly(
                    "waiting_reception_window_id",
                    "store_id",
                    "business_interval_key",
                    "business_date",
                    "accepting_from",
                    "accepting_until",
                    "opened_settings_version",
                    "opened_by_job_id",
                    "opened_at");
            assertThat(indexColumns(connection, "waiting_auto_open_jobs")).contains(
                    "idx_waiting_auto_open_due:status,next_attempt_at,scheduled_at,waiting_auto_open_job_id",
                    "idx_waiting_auto_open_expired_lease:status,lease_until,waiting_auto_open_job_id",
                    "idx_waiting_auto_open_invalidation:store_id,status,expected_settings_version",
                    "uk_waiting_auto_open_interval_version:store_id,business_interval_key,expected_settings_version",
                    "uk_waiting_auto_open_idempotency:idempotency_key");
            assertThat(indexColumns(connection, "waiting_reception_windows")).contains(
                    "idx_waiting_reception_current:store_id,business_date,opened_settings_version,accepting_from,accepting_until",
                    "uk_waiting_reception_interval_version:store_id,business_interval_key,opened_settings_version");
        }
    }

    @Test
    void enforcesJobAndReceptionUniquenessAndStateChecks() throws Exception {
        migrate();
        try (Connection connection = connection()) {
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=0");
            insertJob(connection, 1L, "interval-a", 3L, "a".repeat(64), "PENDING");

            assertThatThrownBy(() -> insertJob(
                    connection,
                    1L,
                    "interval-a",
                    3L,
                    "b".repeat(64),
                    "PENDING")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertJob(
                    connection,
                    2L,
                    "interval-b",
                    3L,
                    "a".repeat(64),
                    "PENDING")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertJob(
                    connection,
                    3L,
                    "interval-c",
                    3L,
                    "c".repeat(64),
                    "UNKNOWN")).isInstanceOf(SQLException.class);

            insertWindow(connection, 1L, 1L, "interval-a", 3L);
            assertThatThrownBy(() -> insertWindow(
                    connection,
                    1L,
                    1L,
                    "interval-a",
                    3L)).isInstanceOf(SQLException.class);
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private static Flyway migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        flyway.migrate();
        return flyway;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
    }

    private static List<String> columns(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (result.next()) {
                    columns.add(result.getString(1));
                }
                return columns;
            }
        }
    }

    private static List<String> indexColumns(Connection connection, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT CONCAT(index_name, ':', GROUP_CONCAT(column_name ORDER BY seq_in_index))
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name <> 'PRIMARY'
                GROUP BY index_name
                ORDER BY index_name
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                List<String> indexes = new ArrayList<>();
                while (result.next()) {
                    indexes.add(result.getString(1));
                }
                return indexes;
            }
        }
    }

    private static void insertJob(
            Connection connection,
            long storeId,
            String intervalKey,
            long settingsVersion,
            String idempotencyKey,
            String status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_auto_open_jobs (
                    store_id, business_interval_key, business_date,
                    interval_starts_at, interval_ends_at, scheduled_at,
                    expected_settings_version, expected_advance_open_minutes,
                    idempotency_key, status, attempt_count, next_attempt_at,
                    fencing_token, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 60, ?, ?, 0, ?, 0, ?, ?)
                """)) {
            Instant start = Instant.parse("2026-08-17T00:00:00Z");
            Instant created = start.minusSeconds(7_200);
            statement.setLong(1, storeId);
            statement.setString(2, intervalKey);
            statement.setObject(3, LocalDate.of(2026, 8, 17));
            statement.setTimestamp(4, Timestamp.from(start));
            statement.setTimestamp(5, Timestamp.from(start.plusSeconds(32_400)));
            statement.setTimestamp(6, Timestamp.from(start.minusSeconds(3_600)));
            statement.setLong(7, settingsVersion);
            statement.setString(8, idempotencyKey);
            statement.setString(9, status);
            statement.setTimestamp(10, Timestamp.from(start.minusSeconds(3_600)));
            statement.setTimestamp(11, Timestamp.from(created));
            statement.setTimestamp(12, Timestamp.from(created));
            statement.executeUpdate();
        }
    }

    private static void insertWindow(
            Connection connection,
            long jobId,
            long storeId,
            String intervalKey,
            long settingsVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_reception_windows (
                    store_id, business_interval_key, business_date,
                    accepting_from, accepting_until, opened_settings_version,
                    opened_by_job_id, opened_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            Instant start = Instant.parse("2026-08-16T23:00:00Z");
            statement.setLong(1, storeId);
            statement.setString(2, intervalKey);
            statement.setObject(3, LocalDate.of(2026, 8, 17));
            statement.setTimestamp(4, Timestamp.from(start));
            statement.setTimestamp(5, Timestamp.from(start.plusSeconds(36_000)));
            statement.setLong(6, settingsVersion);
            statement.setLong(7, jobId);
            statement.setTimestamp(8, Timestamp.from(start));
            statement.executeUpdate();
        }
    }
}
