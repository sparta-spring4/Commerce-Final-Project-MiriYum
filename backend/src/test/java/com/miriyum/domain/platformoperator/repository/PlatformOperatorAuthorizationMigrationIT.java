package com.miriyum.domain.platformoperator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
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
class PlatformOperatorAuthorizationMigrationIT {
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");

    @Test
    @DisplayName("V40은 권한·배정·승인 원장과 singleton guard를 제약과 함께 생성한다")
    void createsAuthorizationLedgersWithConstraints() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)) {
            mysql.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied()).extracting(MigrationInfo::getScript)
                    .contains("V40__create_platform_operator_authorization.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(existingTables(connection)).contains(
                        "platform_operator_role_grants",
                        "platform_operator_permission_grants",
                        "admin_case_assignments",
                        "platform_operator_reauthentication_approvals",
                        "platform_operator_authority_guard");
                assertThat(singleLong(connection,
                        "SELECT COUNT(*) FROM platform_operator_authority_guard WHERE guard_id = 1"))
                        .isEqualTo(1L);

                long accountId = insertAccount(connection);
                insertRoleGrant(connection, accountId, "SUPER_ADMIN");
                assertThatThrownBy(() -> insertRoleGrant(connection, accountId, "SUPER_ADMIN"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertRoleGrant(connection, accountId, "UNREGISTERED_ROLE"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private static long insertAccount(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_accounts
                    (email, password_hash, display_name, status, password_state,
                     temporary_password_expires_at, temporary_password_failure_count,
                     authority_version, session_version, row_version, created_at, updated_at)
                VALUES ('authorization@example.com', 'hash', 'operator', 'ACTIVE', 'ACTIVE',
                        NULL, 0, 1, 1, 0, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertRoleGrant(Connection connection, long accountId, String role) throws SQLException {
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

    private static Set<String> existingTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT table_name FROM information_schema.tables
                      WHERE table_schema = DATABASE()
                     """)) {
            Set<String> names = new java.util.HashSet<>();
            while (resultSet.next()) {
                names.add(resultSet.getString(1).toLowerCase());
            }
            return names;
        }
    }

    private static long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
