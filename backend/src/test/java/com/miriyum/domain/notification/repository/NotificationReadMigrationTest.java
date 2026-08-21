package com.miriyum.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-d")
@Testcontainers
class NotificationReadMigrationTest {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");

    @Test
    void upgradesV67ByBackfillingPublicDeliveredNotificationsAndAddingReadIndexes()
            throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .target(MigrationVersion.fromVersion("67"))
                    .load()
                    .migrate();

            try (Connection connection = mysql.createConnection("")) {
                seedV67Notifications(connection);
            }

            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load()
                    .migrate();

            try (Connection connection = mysql.createConnection("")) {
                assertThat(readAt(connection, 101L))
                        .isEqualTo(LocalDateTime.parse("2026-08-20T01:02:03"));
                assertThat(readAt(connection, 102L)).isNull();
                assertThat(changeVersion(connection, 41L)).isEqualTo(101L);
                assertThat(indexColumnCount(
                        connection, "notification_tasks", "idx_notification_unread"))
                        .isGreaterThanOrEqualTo(4);
                assertThat(indexColumns(
                        connection,
                        "notification_tasks",
                        "idx_notification_public_watermark"
                )).containsExactly(
                        "recipient_account_id",
                        "status",
                        "notification_id"
                );
            }
        }
    }

    private static void seedV67Notifications(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO consumer_accounts (
                        consumer_account_id, email, password_hash, name, status,
                        created_at, updated_at
                    ) VALUES (41, 'read-migration@example.com', 'hash', '읽음이관', 'ACTIVE',
                              NOW(6), NOW(6))
                    """);
            statement.executeUpdate(notificationInsert(
                    101L, "DELIVERED", "픽업 예약이 확정되었습니다.",
                    "2026-08-20 01:02:03.000000"));
            statement.executeUpdate(notificationInsert(
                    102L, "DELIVERED", "픽업 예약이 확정되었습니다.",
                    "2026-08-20 01:02:04.000000"));
            statement.executeUpdate("""
                    INSERT INTO notification_channel_attempts (
                        notification_id, channel, status, attempt_count,
                        last_attempted_at, created_at, updated_at
                    ) VALUES
                        (101, 'IN_APP', 'DELIVERED', 1, NOW(6), NOW(6), NOW(6)),
                        (102, 'IN_APP', 'FAILED', 1, NOW(6), NOW(6), NOW(6))
                    """);
        }
    }

    private static String notificationInsert(
            long notificationId,
            String status,
            String title,
            String deliveredAt
    ) {
        return """
                INSERT INTO notification_tasks (
                    notification_id, source_domain, source_event_id, purpose,
                    recipient_account_id, recipient_relation_version,
                    resource_type, resource_id, resource_version, source_state,
                    occurred_at, scheduled_at, correlation_id, contract_version,
                    payload_fingerprint, status, title, delivered_at
                ) VALUES (
                    %d, 'PICKUP', 'read-migration-%d', 'PICKUP_RESERVATION_CONFIRMED',
                    41, 1, 'PICKUP_RESERVATION', 31, 1, 'CONFIRMED',
                    '2026-08-20 01:00:00', '2026-08-20 01:00:00',
                    'read-migration-correlation-%d', 'notification-source-event-v1',
                    '%064d', '%s', '%s', '%s'
                )
                """.formatted(
                notificationId,
                notificationId,
                notificationId,
                notificationId,
                status,
                title,
                deliveredAt
        );
    }

    private static LocalDateTime readAt(Connection connection, long notificationId)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT read_at FROM notification_tasks WHERE notification_id = ?
                """)) {
            statement.setLong(1, notificationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getObject(1, LocalDateTime.class);
            }
        }
    }

    private static long changeVersion(Connection connection, long accountId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT change_version
                  FROM notification_consumer_change_states
                 WHERE consumer_account_id = ?
                """)) {
            statement.setLong(1, accountId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static int indexColumnCount(
            Connection connection,
            String table,
            String index
    ) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                   AND index_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static List<String> indexColumns(
            Connection connection,
            String table,
            String index
    ) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT column_name
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                   AND index_name = ?
                 ORDER BY seq_in_index
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
                return columns;
            }
        }
    }
}
