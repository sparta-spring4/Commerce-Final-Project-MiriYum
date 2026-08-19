package com.miriyum.domain.platformoperator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
class EnforcementOperatorRoleMigrationIT {
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");

    @Test
    @DisplayName("V60은 기존 제재 운영자 세션만 정확히 한 번 무효화한다")
    void invalidatesOnlyExistingEnforcementOperatorSessionsOnce() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            migrate(mysql, "59").migrate();

            try (Connection connection = mysql.createConnection("")) {
                long enforcementId = insertAccount(connection, "enforcement@example.com", 7, 11, 13);
                long supportId = insertAccount(connection, "support@example.com", 5, 6, 8);
                insertRole(connection, enforcementId, "ENFORCEMENT_OPERATOR");
                insertRole(connection, supportId, "MEMBER_SUPPORT_OPERATOR");
            }

            Flyway upgraded = migrate(mysql, null);
            upgraded.migrate();

            assertThat(upgraded.info().applied()).extracting(MigrationInfo::getScript)
                    .contains("V60__invalidate_enforcement_operator_sessions.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(versions(connection, "enforcement@example.com"))
                        .containsExactly(8L, 12L, 14L);
                assertThat(versions(connection, "support@example.com"))
                        .containsExactly(5L, 6L, 8L);
            }

            upgraded.migrate();

            try (Connection connection = mysql.createConnection("")) {
                assertThat(versions(connection, "enforcement@example.com"))
                        .containsExactly(8L, 12L, 14L);
            }
        }
    }

    private static Flyway migrate(MySQLContainer mysql, String target) {
        var configuration = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static long insertAccount(
            Connection connection,
            String email,
            long authorityVersion,
            long sessionVersion,
            long rowVersion
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_accounts
                    (email, password_hash, display_name, status, password_state,
                     temporary_password_expires_at, temporary_password_failure_count,
                     authority_version, session_version, row_version, created_at, updated_at)
                VALUES (?, 'hash', 'operator', 'ACTIVE', 'ACTIVE', NULL, 0, ?, ?, ?, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setLong(2, authorityVersion);
            statement.setLong(3, sessionVersion);
            statement.setLong(4, rowVersion);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertRole(Connection connection, long accountId, String role) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_role_grants
                    (platform_operator_account_id, role, granted_at)
                VALUES (?, ?, NOW(6))
                """)) {
            statement.setLong(1, accountId);
            statement.setString(2, role);
            statement.executeUpdate();
        }
    }

    private static long[] versions(Connection connection, String email) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT authority_version, session_version, row_version
                  FROM platform_operator_accounts
                 WHERE email = ?
                """)) {
            statement.setString(1, email);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new long[] {
                        resultSet.getLong("authority_version"),
                        resultSet.getLong("session_version"),
                        resultSet.getLong("row_version")
                };
            }
        }
    }
}
