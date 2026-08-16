package com.miriyum.domain.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
class DashboardAnalyticsMigrationTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Test
    void v51CreatesAuthorityVersionAndAtomicMetricKeys() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        flyway.migrate();

        assertThat(flyway.info().applied()).anyMatch(migration ->
                "51".equals(String.valueOf(migration.getVersion()))
                        && "V51__create_dashboard_analytics_snapshots.sql"
                                .equals(migration.getScript()));

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            assertThat(columnType(connection, "stores", "dashboard_authority_version"))
                    .isEqualTo("bigint");
            assertThat(uniqueColumns(
                    connection,
                    "dashboard_analytics_snapshots",
                    "uk_dashboard_analytics_snapshot_identity"))
                    .containsExactly(
                            "store_id",
                            "business_date",
                            "as_of",
                            "store_authority_version");
            assertThat(uniqueColumns(
                    connection,
                    "dashboard_analytics_metric_snapshots",
                    "uk_dashboard_analytics_metric_key"))
                    .containsExactly("dashboard_snapshot_id", "metric_key");
            assertThat(columnNames(connection, "dashboard_analytics_metric_snapshots"))
                    .doesNotContain(
                            "consumer_account_id",
                            "reservation_id",
                            "waiting_team_id");
        }
    }

    private static String columnType(
            Connection connection,
            String table,
            String column
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DATA_TYPE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static List<String> uniqueColumns(
            Connection connection,
            String table,
            String constraint
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COLUMN_NAME
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                  AND NON_UNIQUE = 0
                ORDER BY SEQ_IN_INDEX
                """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            return rows(statement);
        }
    }

    private static List<String> columnNames(Connection connection, String table)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COLUMN_NAME
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """)) {
            statement.setString(1, table);
            return rows(statement);
        }
    }

    private static List<String> rows(PreparedStatement statement) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
