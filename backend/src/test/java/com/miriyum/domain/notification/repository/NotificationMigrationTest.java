package com.miriyum.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-d")
@Testcontainers
class NotificationMigrationTest {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");

    @Test
    void createsTheNotificationLedgerWithDatabaseEnforcedLogicalIdentity() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied())
                    .extracting(MigrationInfo::getScript)
                    .contains("V33__create_notification_runtime.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(tableExists(connection, "notification_tasks")).isTrue();
                assertThat(tableExists(connection, "notification_channel_attempts")).isTrue();
                assertThat(tableExists(connection, "notification_task_transition_audits")).isTrue();
                assertThat(indexColumnCount(connection, "notification_tasks", "uk_notification_logical_event"))
                        .isEqualTo(7);
                assertThat(indexColumnCount(connection, "notification_channel_attempts", "uk_notification_channel"))
                        .isEqualTo(2);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static int indexColumnCount(
            Connection connection,
            String table,
            String index
    ) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                       FROM information_schema.statistics
                      WHERE table_schema = DATABASE()
                        AND table_name = '%s'
                        AND index_name = '%s'
                     """.formatted(table, index))) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
