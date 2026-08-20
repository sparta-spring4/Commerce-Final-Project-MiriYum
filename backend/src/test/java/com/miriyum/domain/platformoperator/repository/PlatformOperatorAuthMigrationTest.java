package com.miriyum.domain.platformoperator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
class PlatformOperatorAuthMigrationTest {

    @Test
    void createsConstrainedAccountAndSecretFreeEventLedgers() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.0.40"))
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied()).extracting(MigrationInfo::getScript)
                    .contains("V39__create_platform_operator_auth.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(tableExists(connection, "platform_operator_accounts")).isTrue();
                assertThat(tableExists(connection, "platform_operator_auth_events")).isTrue();
                assertThat(columns(connection, "platform_operator_auth_events"))
                        .noneMatch(name -> Set.of("password", "token", "session_id", "request_body", "secret")
                                .stream().anyMatch(name::contains));

                insertAccount(connection, "operator@example.com", 0);
                assertThatThrownBy(() -> insertAccount(connection, "operator@example.com", 0))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertAccount(connection, "other@example.com", -1))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private static void insertAccount(Connection connection, String email, int failures) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_accounts
                    (email, password_hash, display_name, status, password_state,
                     temporary_password_expires_at, temporary_password_failure_count,
                     authority_version, session_version, row_version, created_at, updated_at)
                VALUES (?, 'hash', 'operator', 'ACTIVE', 'TEMPORARY', NOW(6), ?, 1, 1, 0, NOW(6), NOW(6))
                """)) {
            statement.setString(1, email);
            statement.setInt(2, failures);
            statement.executeUpdate();
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = '%s'
                """.formatted(table))) {
            var names = new java.util.HashSet<String>();
            while (resultSet.next()) names.add(resultSet.getString(1).toLowerCase());
            return names.stream().collect(Collectors.toUnmodifiableSet());
        }
    }
}
