package com.miriyum.domain.platformoperator.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
class OnboardingDecisionConcurrencyIT {
    @Test
    void concurrentApproveAndRejectPersistOneDecision() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.0.40")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load().migrate();
            seed(mysql);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var approve = executor.submit(() -> insert(mysql, "APPROVE", ready, start));
                var reject = executor.submit(() -> insert(mysql, "REJECT", ready, start));
                ready.await();
                start.countDown();
                assertThat(List.of(approve.get(), reject.get())).containsExactlyInAnyOrder(false, true);
            }
            try (Connection connection = mysql.createConnection("");
                    var result = connection.createStatement().executeQuery(
                            "SELECT COUNT(*) FROM store_onboarding_decisions")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1L);
            }
        }
    }

    private static void seed(MySQLContainer mysql) throws Exception {
        try (Connection connection = mysql.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            statement.execute("""
                    INSERT INTO store_onboarding_review_cases (
                        case_public_id, store_onboarding_application_id, application_version,
                        case_type, status, case_version, active_marker, created_at, updated_at
                    ) VALUES ('550e8400-e29b-41d4-a716-446655440277', 41, 1,
                        'ONBOARDING', 'UNDER_REVIEW', 2, 1, NOW(6), NOW(6))
                    """);
        }
    }

    private static boolean insert(
            MySQLContainer mysql, String decision, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try (Connection connection = mysql.createConnection("");
                var statement = connection.prepareStatement("""
                        INSERT INTO store_onboarding_decisions (
                            decision_public_id, case_public_id, case_version,
                            decided_by_platform_operator_id, decision_type, reason_code,
                            idempotency_key, created_at
                        ) VALUES (?, '550e8400-e29b-41d4-a716-446655440277', 2, 91, ?, 'TEST', ?, NOW(6))
                        """)) {
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS=0");
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, decision);
            statement.setString(3, UUID.randomUUID().toString());
            try {
                statement.executeUpdate();
                return true;
            } catch (java.sql.SQLException duplicate) {
                return false;
            }
        }
    }
}
