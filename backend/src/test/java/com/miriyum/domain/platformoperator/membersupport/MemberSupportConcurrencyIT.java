package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
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
class MemberSupportConcurrencyIT {
    @Test
    void recoveryAndSanctionCompareAndSetHaveExactlyOneWinner() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.0.40")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load().migrate();
            long accountId;
            try (Connection connection = mysql.createConnection("");
                 var statement = connection.prepareStatement("""
                         insert into consumer_accounts
                           (email, password_hash, name, password_reset_required, support_version,
                            status, created_at, updated_at)
                         values ('race@example.com', 'hash', 'race', false, 0, 'ACTIVE', now(6), now(6))
                         """, Statement.RETURN_GENERATED_KEYS)) {
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) { keys.next(); accountId = keys.getLong(1); }
            }
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var recovery = executor.submit(() -> cas(mysql, accountId, true, ready, start));
                var sanction = executor.submit(() -> cas(mysql, accountId, false, ready, start));
                ready.await();
                start.countDown();
                List<Integer> results = List.of(recovery.get(), sanction.get());
                assertThat(results).containsExactlyInAnyOrder(0, 1);
            }
            try (Connection connection = mysql.createConnection("");
                 var statement = connection.prepareStatement(
                         "select support_version from consumer_accounts where consumer_account_id = ?")) {
                statement.setLong(1, accountId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getLong(1)).isEqualTo(1);
                }
            }
        }
    }

    private int cas(MySQLContainer mysql, long accountId, boolean recovery,
                    CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = mysql.createConnection("");
             var statement = connection.prepareStatement(recovery ? """
                     update consumer_accounts
                        set email = 'recovered@example.com', password_reset_required = true,
                            support_version = support_version + 1
                      where consumer_account_id = ? and support_version = 0
                     """ : """
                     update consumer_accounts
                        set status = 'SUSPENDED', support_version = support_version + 1
                      where consumer_account_id = ? and support_version = 0
                     """)) {
            statement.setLong(1, accountId);
            ready.countDown();
            start.await();
            return statement.executeUpdate();
        }
    }
}
