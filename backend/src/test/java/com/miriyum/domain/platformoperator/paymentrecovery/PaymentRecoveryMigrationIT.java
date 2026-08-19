package com.miriyum.domain.platformoperator.paymentrecovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
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
class PaymentRecoveryMigrationIT {

    @Test
    void v64CreatesImmutableVersionedPaymentRecoveryWorkflow() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.0.40")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied())
                    .extracting(MigrationInfo::getScript)
                    .contains("V64__create_payment_recovery_workflow.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(tables(connection)).contains(
                        "payment_recovery_cases",
                        "payment_recovery_proposals",
                        "payment_recovery_approvals",
                        "payment_recovery_executions");
                assertThat(columns(connection, "payment_recovery_cases")).contains(
                        "case_public_id", "handoff_id", "lineage_id", "case_sequence",
                        "status", "case_version", "current_proposal_version", "row_version");
                assertThat(columns(connection, "payment_recovery_executions")).contains(
                        "execution_key", "operation", "status", "lease_owner", "lease_token",
                        "lease_expires_at", "lookup_attempt_count", "row_version");
                assertThat(constraints(connection)).contains(
                        "uk_payment_recovery_cases_handoff",
                        "uk_payment_recovery_proposals_version",
                        "uk_payment_recovery_approvals_actor",
                        "uk_payment_recovery_executions_key");
                assertThat(triggers(connection)).contains(
                        "trg_payment_recovery_proposals_no_update",
                        "trg_payment_recovery_proposals_no_delete",
                        "trg_payment_recovery_approvals_no_update",
                        "trg_payment_recovery_approvals_no_delete");
                assertThat(checkClause(connection, "ck_platform_operator_audit_events_action"))
                        .contains("PAYMENT_RECOVERY_CASE_CREATED", "PAYMENT_RECOVERY_EXECUTED");
                assertThat(checkClause(connection, "ck_platform_operator_audit_events_reason"))
                        .contains("PAYMENT_RECOVERY");
            }
        }
    }

    private static Set<String> tables(Connection connection) throws SQLException {
        return names(connection,
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()");
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                Set<String> names = new HashSet<>();
                while (result.next()) names.add(result.getString(1).toLowerCase());
                return names;
            }
        }
    }

    private static Set<String> constraints(Connection connection) throws SQLException {
        return names(connection, """
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema=DATABASE()
                """);
    }

    private static Set<String> triggers(Connection connection) throws SQLException {
        return names(connection,
                "SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema=DATABASE()");
    }

    private static String checkClause(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT check_clause FROM information_schema.check_constraints
                WHERE constraint_schema=DATABASE() AND constraint_name=?
                """)) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static Set<String> names(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString(1).toLowerCase());
            return names;
        }
    }
}
