package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void v46CreatesMemberSupportLedgersAndPreservesAllReauthenticationPurposes() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.0.40")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied()).extracting(MigrationInfo::getScript)
                    .contains("V46__create_member_support.sql");
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
                        .contains("password_reset_verification_id", "password_reset_completed_at",
                                "active_case_scope_id");

                long operatorId = insertOperator(connection);
                try (var statement = connection.prepareStatement("""
                        INSERT INTO platform_operator_permission_grants
                            (platform_operator_account_id, permission, granted_at)
                        VALUES (?, 'ACCOUNT_PERMANENT_SANCTION_APPROVE', NOW(6))
                        """)) {
                    statement.setLong(1, operatorId);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                assertReauthenticationPurposeAccepted(
                        connection, operatorId, "ACCOUNT_APPEAL_DECISION", "a");
                assertReauthenticationPurposeAccepted(
                        connection, operatorId, "PERMANENT_ACCOUNT_SANCTION_APPROVAL", "b");
                assertAppealUniquenessIsScopedToSourceSanction(connection, operatorId);
            }
        }
    }

    private static void assertAppealUniquenessIsScopedToSourceSanction(
            Connection connection, long operatorId) throws SQLException {
        long accountId = insertConsumer(connection);
        long firstEnforcement = insertCase(
                connection, "enforcement-1", "ACCOUNT_SANCTION", accountId, null, "APPROVED");
        long secondEnforcement = insertCase(
                connection, "enforcement-2", "ACCOUNT_SANCTION", accountId, null, "APPROVED");
        long firstSanction = insertSanction(
                connection, "sanction-1", firstEnforcement, accountId,
                "TEMPORARY_SUSPENSION", operatorId);
        long secondSanction = insertSanction(
                connection, "sanction-2", secondEnforcement, accountId,
                "PERMANENT_SUSPENSION", operatorId);

        assertThat(insertCase(
                connection, "appeal-1", "ACCOUNT_APPEAL", accountId, firstSanction, "SUBMITTED"))
                .isPositive();
        assertThat(insertCase(
                connection, "appeal-2", "ACCOUNT_APPEAL", accountId, secondSanction, "SUBMITTED"))
                .isPositive();
        assertThatThrownBy(() -> insertCase(
                connection, "appeal-1-duplicate", "ACCOUNT_APPEAL", accountId,
                firstSanction, "SUBMITTED"))
                .isInstanceOf(SQLException.class);
    }

    private static long insertConsumer(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO consumer_accounts
                    (email, password_hash, name, password_reset_required, support_version,
                     status, created_at, updated_at)
                VALUES ('appeal-scope@example.com', 'hash', 'appeal-scope', FALSE, 0,
                        'ACTIVE', NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertCase(Connection connection, String publicId, String type,
                                   long accountId, Long sourceSanctionId, String status)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO member_support_cases
                    (case_public_id, case_type, account_type, account_id, source_sanction_id,
                     status, target_support_version, row_version, submitted_at, created_at, updated_at)
                VALUES (?, ?, 'CONSUMER', ?, ?, ?, 0, 1, NOW(6), NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, publicId);
            statement.setString(2, type);
            statement.setLong(3, accountId);
            if (sourceSanctionId == null) statement.setNull(4, java.sql.Types.BIGINT);
            else statement.setLong(4, sourceSanctionId);
            statement.setString(5, status);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertSanction(Connection connection, String publicId, long caseId,
                                       long accountId, String level, long operatorId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO member_sanctions
                    (sanction_public_id, member_support_case_id, account_type, account_id,
                     level, status, restricted_features, reason_code, policy_version,
                     proposed_by_operator_id, applied_by_operator_id, proposed_at, applied_at,
                     ends_at, row_version, created_at, updated_at)
                VALUES (?, ?, 'CONSUMER', ?, ?, 'APPLIED', JSON_ARRAY(), 'ABUSE', 'v1',
                        ?, ?, NOW(6), NOW(6), NULL, 1, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, publicId);
            statement.setLong(2, caseId);
            statement.setLong(3, accountId);
            statement.setString(4, level);
            statement.setLong(5, operatorId);
            statement.setLong(6, operatorId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void assertReauthenticationPurposeAccepted(
            Connection connection, long operatorId, String purpose, String digestPrefix
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_reauthentication_approvals
                    (approval_digest, platform_operator_account_id, purpose, target_type, target_id,
                     session_fingerprint, authority_version, issued_at, expires_at, consumed_at)
                VALUES (?, ?, ?, 'CONSUMER_ACCOUNT', '1', REPEAT('f', 64), 1,
                        NOW(6), DATE_ADD(NOW(6), INTERVAL 5 MINUTE), NULL)
                """)) {
            statement.setString(1, digestPrefix.repeat(64));
            statement.setLong(2, operatorId);
            statement.setString(3, purpose);
            assertThat(statement.executeUpdate()).isEqualTo(1);
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
