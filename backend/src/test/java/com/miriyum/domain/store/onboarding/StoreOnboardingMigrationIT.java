package com.miriyum.domain.store.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class StoreOnboardingMigrationIT {

    @Test
    void v68CreatesVersionedWorkflowAndAllowsOnlyOneActiveCase() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.0.40")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            flyway.migrate();

            assertThat(flyway.info().applied())
                    .extracting(MigrationInfo::getScript)
                    .contains("V68__create_store_onboarding_review_workflow.sql");

            try (Connection connection = mysql.createConnection("")) {
                assertThat(tables(connection)).contains(
                        "store_onboarding_applications",
                        "store_onboarding_application_versions",
                        "store_onboarding_automatic_check_jobs",
                        "store_onboarding_review_cases",
                        "store_onboarding_decisions");
                assertThat(constraints(connection)).contains(
                        "uk_store_onboarding_submission",
                        "uk_store_onboarding_version",
                        "uk_store_onboarding_active_case",
                        "uk_store_onboarding_terminal_decision",
                        "uk_store_onboarding_result_store");
                assertThat(columns(connection, "store_onboarding_automatic_check_jobs")).contains(
                        "lease_owner", "lease_token", "lease_expires_at",
                        "attempt_count", "next_attempt_at", "row_version");
                assertThat(triggers(connection)).contains(
                        "trg_store_onboarding_versions_no_update",
                        "trg_store_onboarding_versions_no_delete",
                        "trg_store_onboarding_decisions_no_update",
                        "trg_store_onboarding_decisions_no_delete");

                assertActiveCaseConstraint(connection);
            }
        }
    }

    private static void assertActiveCaseConstraint(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            statement.execute("""
                    INSERT INTO store_onboarding_review_cases (
                        case_public_id, store_onboarding_application_id, application_version,
                        case_type, status, case_version, active_marker, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000001', 41, 1,
                        'ONBOARDING', 'REVIEW_READY', 1, 1, NOW(6), NOW(6)
                    )
                    """);

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO store_onboarding_review_cases (
                        case_public_id, store_onboarding_application_id, application_version,
                        case_type, status, case_version, active_marker, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000002', 41, 1,
                        'OWNERSHIP_CONFLICT', 'UNDER_REVIEW', 1, 1, NOW(6), NOW(6)
                    )
                    """))
                    .isInstanceOf(SQLException.class);

            assertThat(statement.executeUpdate("""
                    INSERT INTO store_onboarding_review_cases (
                        case_public_id, store_onboarding_application_id, application_version,
                        case_type, status, case_version, active_marker, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000003', 41, 1,
                        'ONBOARDING', 'CLOSED', 2, NULL, NOW(6), NOW(6)
                    )
                    """))
                    .isEqualTo(1);
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

    private static Set<String> names(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString(1).toLowerCase());
            return names;
        }
    }
}
