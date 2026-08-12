package com.miriyum.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
@Testcontainers
class PaymentMigrationTest {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");

    @Test
    @DisplayName("실제 MySQL V28 데이터를 보존하며 Payment V30과 분리 공개 ID 채번을 적용한다")
    void upgradesV28ToPaymentRuntimeV30() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .target(MigrationVersion.fromVersion("28"))
                    .load()
                    .migrate();
            insertExistingConsumer(mysql);

            Flyway upgraded = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            upgraded.migrate();

            assertThat(upgraded.info().applied())
                    .extracting(MigrationInfo::getScript)
                    .contains("V30__create_payment_runtime.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(singleLong(connection,
                        "SELECT COUNT(*) FROM consumer_accounts WHERE consumer_account_id = 10001"))
                        .isEqualTo(1L);
                assertThat(nextReference(connection, "payment_public_ids"))
                        .isEqualTo(900_000_000_000_000_001L);
                assertThat(nextReference(connection, "refund_public_ids"))
                        .isEqualTo(910_000_000_000_000_001L);
                insertPayment(connection, "900000000000000001", "payment-reservation-900000000000000001");
                assertThatThrownBy(() -> insertPayment(
                        connection,
                        "900000000000000002",
                        "payment-reservation-900000000000000002"
                )).isInstanceOf(Exception.class);
            }
        }
    }

    private static void insertExistingConsumer(MySQLContainer mysql) throws Exception {
        try (Connection connection = mysql.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO consumer_accounts (
                         consumer_account_id, email, password_hash, name, status, created_at, updated_at
                     ) VALUES (10001, 'payment-migration@example.com', 'hash', '결제회원',
                               'ACTIVE', NOW(6), NOW(6))
                     """)) {
            statement.executeUpdate();
        }
    }

    private static long nextReference(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + table + " () VALUES ()");
            try (ResultSet resultSet = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long singleLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static void insertPayment(
            Connection connection,
            String paymentId,
            String portOnePaymentId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO payments (
                    payment_id, source_type, source_reference_id, source_policy_version,
                    source_expires_at, preparation_idempotency_key,
                    preparation_request_fingerprint, consumer_account_id,
                    amount_minor, refunded_amount_minor, currency, portone_payment_id,
                    order_name,
                    status, last_attempt_status, created_at, updated_at, version
                ) VALUES (?, 'RESERVATION_DEPOSIT', '123', 7,
                          DATE_ADD(NOW(6), INTERVAL 1 HOUR), ?,
                          REPEAT('a', 64), 10001,
                          30000, 0, 'KRW', ?, 'MiriYum 예약금 123',
                          'READY', 'NOT_STARTED', NOW(6), NOW(6), 0)
                """)) {
            statement.setString(1, paymentId);
            statement.setString(2, "550e8400-e29b-41d4-a716-" + paymentId.substring(7));
            statement.setString(3, portOnePaymentId);
            statement.executeUpdate();
        }
    }
}
