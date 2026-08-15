package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
class MemberSupportMigrationIT {

    @Test
    void v42CreatesMemberSupportLedgersAndAccountGuards() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.0.40")) {
            mysql.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied()).extracting(MigrationInfo::getScript)
                    .contains("V43__create_member_support.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(columns(connection, "consumer_accounts"))
                        .contains("password_reset_required", "support_version");
                assertThat(columns(connection, "store_operator_accounts"))
                        .contains("password_reset_required", "support_version");
                assertThat(tables(connection)).contains(
                        "member_identity_verifications",
                        "member_support_cases",
                        "member_sanctions",
                        "member_sanction_approvals",
                        "member_support_audits");
                assertThat(columns(connection, "member_support_audits"))
                        .contains("retention_until")
                        .doesNotContain("email", "phone", "password", "approval");
                assertThat(columns(connection, "member_sanctions")).contains("sanction_public_id");
                assertThat(columns(connection, "member_identity_verifications"))
                        .contains("source_sanction_id", "consumed_at", "expires_at");
                assertThat(columns(connection, "member_support_cases"))
                        .contains("password_reset_verification_id", "password_reset_completed_at");

                long operatorId = insertOperator(connection);
                try (var statement = connection.prepareStatement("""
                        INSERT INTO platform_operator_permission_grants
                            (platform_operator_account_id, permission, granted_at)
                        VALUES (?, 'ACCOUNT_PERMANENT_SANCTION_APPROVE', NOW(6))
                        """)) {
                    statement.setLong(1, operatorId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
            }
        }
    }

    private static Set<String> tables(Connection connection) throws SQLException {
        return names(connection, """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                """);
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> names = new HashSet<>();
                while (resultSet.next()) names.add(resultSet.getString(1).toLowerCase());
                return names;
            }
        }
    }

    private static Set<String> names(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            Set<String> names = new HashSet<>();
            while (resultSet.next()) names.add(resultSet.getString(1).toLowerCase());
            return names;
        }
    }

    private static long insertOperator(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_accounts
                    (email, password_hash, display_name, status, password_state,
                     temporary_password_expires_at, temporary_password_failure_count,
                     authority_version, session_version, row_version, created_at, updated_at)
                VALUES ('member-support-migration@example.com', 'hash', 'member-support', 'ACTIVE', 'ACTIVE',
                        NULL, 0, 1, 1, 0, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}
