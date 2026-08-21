package com.miriyum.domain.store.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
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
class StoreOnboardingAutomaticCheckConcurrencyIT {

    @Test
    void concurrentClaimsHaveOneWinnerAndStaleFenceCannotRecord() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.0.40")
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load().migrate();
            seedJob(mysql);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> claim(mysql, "worker-a", ready, start));
                var second = executor.submit(() -> claim(mysql, "worker-b", ready, start));
                ready.await();
                start.countDown();
                assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
            }

            try (Connection connection = mysql.createConnection("");
                    var query = connection.createStatement().executeQuery("""
                            SELECT lease_owner, lease_token
                            FROM store_onboarding_automatic_check_jobs
                            WHERE store_onboarding_automatic_check_job_id = 1
                            """)) {
                assertThat(query.next()).isTrue();
                String winner = query.getString(1);
                long token = query.getLong(2);
                String staleOwner = "worker-a".equals(winner) ? "worker-b" : "worker-a";
                try (var stale = connection.prepareStatement("""
                        UPDATE store_onboarding_automatic_check_jobs
                        SET status='PASSED'
                        WHERE store_onboarding_automatic_check_job_id=1
                          AND lease_owner=? AND lease_token=?
                        """)) {
                    stale.setString(1, staleOwner);
                    stale.setLong(2, token);
                    assertThat(stale.executeUpdate()).isZero();
                }
            }
        }
    }

    private static void seedJob(MySQLContainer mysql) throws Exception {
        try (Connection connection = mysql.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            statement.execute("""
                    INSERT INTO store_onboarding_automatic_check_jobs (
                        store_onboarding_automatic_check_job_id,
                        store_onboarding_application_id, application_version, status,
                        lease_token, attempt_count, next_attempt_at, created_at, updated_at
                    ) VALUES (1, 41, 1, 'PENDING', 0, 0, NOW(6), NOW(6), NOW(6))
                    """);
        }
    }

    private static int claim(
            MySQLContainer mysql,
            String owner,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        try (Connection connection = mysql.createConnection("");
                var statement = connection.prepareStatement("""
                        UPDATE store_onboarding_automatic_check_jobs
                        SET status='PROCESSING', lease_owner=?, lease_token=lease_token+1,
                            lease_expires_at=DATE_ADD(NOW(6), INTERVAL 30 SECOND)
                        WHERE store_onboarding_automatic_check_job_id=1
                          AND status='PENDING' AND next_attempt_at <= NOW(6)
                        """)) {
            statement.setString(1, owner);
            return statement.executeUpdate();
        }
    }
}
