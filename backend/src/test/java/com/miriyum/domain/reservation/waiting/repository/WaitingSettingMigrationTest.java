package com.miriyum.domain.reservation.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.*;
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
class WaitingSettingMigrationTest {
    @Container static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Test
    void appliesV46AndEnforcesTheSettingChecks() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        flyway.migrate();

        assertThat(flyway.info().applied()).anyMatch(migration ->
                "46".equals(String.valueOf(migration.getVersion()))
                        && "V46__create_waiting_settings.sql".equals(migration.getScript()));

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=0");
            insert(connection, 1L, true, "AUTO", 0, 1L);
            insert(connection, 2L, true, "MANUAL", 180, 1L);
            assertThatThrownBy(() -> insert(connection, 3L, false, "AUTO", 60, 1L))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insert(connection, 4L, true, "AUTO", 181, 1L))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insert(connection, 1L, false, "PAUSED", 60, 2L))
                    .isInstanceOf(SQLException.class);
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private static void insert(Connection connection, long storeId, boolean enabled,
            String mode, int minutes, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_settings (
                    store_id, enabled, reception_mode, advance_open_minutes,
                    version, lock_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """)) {
            statement.setLong(1, storeId);
            statement.setBoolean(2, enabled);
            statement.setString(3, mode);
            statement.setInt(4, minutes);
            statement.setLong(5, version);
            statement.executeUpdate();
        }
    }
}
