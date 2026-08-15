package com.miriyum.domain.platformoperator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class PlatformOperatorManagementAuditMigrationIT {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");

    @Test
    @DisplayName("V43은 단일 슈퍼관리자와 수정·삭제 불가 감사 원장을 MySQL에서 강제한다")
    void migration_enforcesSingletonSuperAdminAndImmutableAuditLedger() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied()).extracting(MigrationInfo::getScript)
                    .contains("V43__create_platform_operator_management_audit.sql");

            try (Connection connection = mysql.createConnection("")) {
                long superAdminId = insertAccount(connection, "super-admin@example.com");
                long operatorId = insertAccount(connection, "operator@example.com");
                insertRoleGrant(connection, superAdminId, "SUPER_ADMIN");

                assertThatThrownBy(() -> insertRoleGrant(connection, operatorId, "SUPER_ADMIN"))
                        .isInstanceOf(SQLException.class);

                insertAuditReviewAssignment(connection, operatorId);
                insertApproval(connection, superAdminId, "OPERATOR_CREATION", "PLATFORM_OPERATOR_ACCOUNT", "new-operator");
                insertApproval(connection, superAdminId, "AUDIT_CORRECTION", "AUDIT_EVENT", "ADMIN:1");

                long originalId = insertAuditEvent(connection, superAdminId, operatorId, null);
                long correctionId = insertAuditEvent(connection, superAdminId, operatorId, originalId);
                long secondCorrectionId = insertAuditEvent(connection, superAdminId, operatorId, originalId);

                assertThat(singleLong(connection, "SELECT original_event_id FROM platform_operator_audit_events "
                        + "WHERE platform_operator_audit_event_id = " + correctionId)).isEqualTo(originalId);
                assertThat(secondCorrectionId).isGreaterThan(correctionId);
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM platform_operator_audit_events "
                        + "WHERE original_event_source = 'ADMIN' AND original_event_id = " + originalId))
                        .isEqualTo(2L);
                assertThatThrownBy(() -> executeUpdate(connection,
                        "UPDATE platform_operator_audit_events SET outcome = 'FAILED' "
                                + "WHERE platform_operator_audit_event_id = " + originalId))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeUpdate(connection,
                        "DELETE FROM platform_operator_audit_events "
                                + "WHERE platform_operator_audit_event_id = " + originalId))
                        .isInstanceOf(SQLException.class);
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM platform_operator_audit_events "
                        + "WHERE platform_operator_audit_event_id = " + originalId)).isEqualTo(1L);
            }
        }
    }

    private static long insertAccount(Connection connection, String email) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_accounts
                    (email, password_hash, display_name, status, password_state,
                     temporary_password_expires_at, temporary_password_failure_count,
                     authority_version, session_version, row_version, created_at, updated_at)
                VALUES (?, 'hash', 'operator', 'ACTIVE', 'ACTIVE',
                        NULL, 0, 1, 1, 0, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
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

    private static void insertAuditReviewAssignment(Connection connection, long accountId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO admin_case_assignments
                    (case_type, case_id, case_version, platform_operator_account_id,
                     status, expires_at, row_version, created_at, updated_at)
                VALUES ('AUDIT_REVIEW', 'audit-review-1', 1, ?, 'ASSIGNED',
                        DATE_ADD(NOW(6), INTERVAL 10 MINUTE), 0, NOW(6), NOW(6))
                """)) {
            statement.setLong(1, accountId);
            statement.executeUpdate();
        }
    }

    private static void insertApproval(
            Connection connection,
            long accountId,
            String purpose,
            String targetType,
            String targetId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_reauthentication_approvals
                    (approval_digest, platform_operator_account_id, purpose, target_type, target_id,
                     session_fingerprint, authority_version, issued_at, expires_at, consumed_at)
                VALUES (?, ?, ?, ?, ?, REPEAT('b', 64), 1, NOW(6),
                        DATE_ADD(NOW(6), INTERVAL 10 MINUTE), NULL)
                """)) {
            statement.setString(1, java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", ""));
            statement.setLong(2, accountId);
            statement.setString(3, purpose);
            statement.setString(4, targetType);
            statement.setString(5, targetId);
            statement.executeUpdate();
        }
    }

    private static long insertAuditEvent(
            Connection connection,
            long actorId,
            long targetId,
            Long originalEventId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operator_audit_events
                    (actor_platform_operator_account_id, actor_authority_version, actor_roles, actor_permissions,
                     action, outcome, reason, target_type, target_id,
                     before_roles, after_roles, before_permissions, after_permissions,
                     original_event_source, original_event_id, correlation_id, occurred_at)
                VALUES (?, 1, JSON_ARRAY('SUPER_ADMIN'), JSON_ARRAY('OPERATOR_AUTHORITY_MANAGE'),
                        ?, 'SUCCESS', ?, 'PLATFORM_OPERATOR_ACCOUNT', ?,
                        JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), ?, ?, ?, NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, actorId);
            statement.setString(2, originalEventId == null ? "ACCOUNT_CREATED" : "AUDIT_CORRECTION");
            statement.setString(3, originalEventId == null ? "ACCOUNT_PROVISIONING" : "RECORD_CORRECTION");
            statement.setString(4, Long.toString(targetId));
            if (originalEventId == null) {
                statement.setNull(5, java.sql.Types.VARCHAR);
                statement.setNull(6, java.sql.Types.BIGINT);
            } else {
                statement.setString(5, "ADMIN");
                statement.setLong(6, originalEventId);
            }
            statement.setString(7, java.util.UUID.randomUUID().toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
