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
@Tag("integration-shard-d")
@Testcontainers
class PaymentMigrationTest {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");

    @Test
    @DisplayName("실제 MySQL V28 데이터를 보존하며 Payment V30과 분리 공개 ID 채번을 적용한다")
    void upgradesV28ToPaymentRuntimeV30() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)
                .withCommand("--log-bin-trust-function-creators=1")) {
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

    @Test
    @DisplayName("V53의 기존 환불을 보존하며 V58 처분 원장과 재시도 횟수를 추가한다")
    void upgradesExistingRefundToReservationDepositDispositionV58() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .target(MigrationVersion.fromVersion("53"))
                    .load()
                    .migrate();
            insertExistingConsumer(mysql);
            try (Connection connection = mysql.createConnection("")) {
                insertPayment(
                        connection,
                        "900000000000000001",
                        "payment-reservation-900000000000000001");
                insertExistingFailedRefund(connection);
            }

            Flyway upgraded = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            upgraded.migrate();

            assertThat(upgraded.info().applied())
                    .extracting(MigrationInfo::getScript)
                    .contains("V58__create_reservation_deposit_dispositions.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(singleLong(connection, """
                        SELECT attempt_count FROM payment_refunds
                         WHERE refund_id = '910000000000000001'
                        """)).isEqualTo(1L);
                assertThat(singleLong(connection, """
                        SELECT COUNT(*) FROM payment_refunds
                         WHERE processing_started_at = requested_at
                        """)).isEqualTo(1L);
                assertThat(singleLong(connection, """
                        SELECT COUNT(*) FROM information_schema.tables
                         WHERE table_schema = DATABASE()
                           AND table_name = 'reservation_deposit_dispositions'
                        """)).isEqualTo(1L);
                insertUuidV7Disposition(connection);
                insertCorrectionDisposition(
                        connection,
                        "019198c0-2e2a-7f7b-8e1c-123456789abd",
                        "reservation:123:correction:1",
                        "PROCESSING",
                        null);
                assertThatThrownBy(() -> insertCorrectionDisposition(
                        connection,
                        "019198c0-2e2a-7f7b-8e1c-123456789abe",
                        "reservation:123:correction:2",
                        "PROCESSING",
                        null)).isInstanceOf(Exception.class);
                insertCorrectionDisposition(
                        connection,
                        "019198c0-2e2a-7f7b-8e1c-123456789abf",
                        "reservation:123:correction:rejected",
                        "FAILED",
                        "PERMANENT");
                insertRefundRetryLedger(connection);
                assertThat(singleLong(connection, """
                        SELECT COUNT(*) FROM payment_ledger_entries
                         WHERE entry_type = 'REFUND_RETRY_REQUESTED'
                        """)).isEqualTo(1L);
            }
        }
    }

    @Test
    @DisplayName("V59 데이터를 보존하며 V62 수동 복구 handoff와 전달 outbox를 추가한다")
    void upgradesV59ToPaymentRecoveryV62() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .target(MigrationVersion.fromVersion("59"))
                    .load()
                    .migrate();

            Flyway upgraded = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            upgraded.migrate();

            assertThat(upgraded.info().applied())
                    .extracting(MigrationInfo::getScript)
                    .contains("V62__create_payment_recovery_handoffs.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(tableCount(connection, "payment_recovery_handoffs"))
                        .isEqualTo(1L);
                assertThat(tableCount(connection, "reservation_payment_recovery_outbox"))
                        .isEqualTo(1L);
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

    private static long tableCount(Connection connection, String tableName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                """)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
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

    private static void insertExistingFailedRefund(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO payment_refunds (
                    refund_id, payment_pk, idempotency_key, request_fingerprint,
                    source_event_id, amount_minor, currency, status, reason_code,
                    policy_version, requested_at, updated_at, version
                ) SELECT '910000000000000001', payment_pk,
                         '550e8400-e29b-41d4-a716-446655440001', REPEAT('b', 64),
                         'reservation:123:cancelled', 10000, 'KRW', 'FAILED',
                         'RESERVATION_CANCELLED', 7, NOW(6), NOW(6), 0
                    FROM payments WHERE payment_id = '900000000000000001'
                """)) {
            statement.executeUpdate();
        }
    }

    private static void insertRefundRetryLedger(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO payment_ledger_entries (
                    payment_pk, payment_refund_pk, event_key, entry_type,
                    amount_minor, currency, occurred_at
                ) SELECT payment_pk, payment_refund_pk,
                         'refund-retry-requested:910000000000000001:2',
                         'REFUND_RETRY_REQUESTED', 10000, 'KRW', NOW(6)
                    FROM payment_refunds WHERE refund_id = '910000000000000001'
                """)) {
            statement.executeUpdate();
        }
    }

    private static void insertUuidV7Disposition(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reservation_deposit_dispositions (
                    disposition_id, payment_pk, source_event_id, source_event_type,
                    policy_version, responsibility_code,
                    target_refund_rate_basis_points, original_amount_minor,
                    target_refund_amount_minor, incremental_refund_amount_minor,
                    completed_refund_amount_minor, withheld_amount_minor, currency,
                    idempotency_key, request_fingerprint, status,
                    attempt_count, requested_at, updated_at, completed_at, version
                ) SELECT '019198c0-2e2a-7f7b-8e1c-123456789abc', payment_pk,
                         'reservation:123:no-refund', 'RESERVATION_CANCELLED',
                         7, 'CONSUMER', 0, 30000, 0, 0, 0, 30000, 'KRW',
                         '019198c0-2e2a-7f7b-8e1c-123456789abc', REPEAT('c', 64),
                         'COMPLETED', 0, NOW(6), NOW(6), NOW(6), 0
                    FROM payments WHERE payment_id = '900000000000000001'
                """)) {
            statement.executeUpdate();
        }
    }

    private static void insertCorrectionDisposition(
            Connection connection,
            String dispositionId,
            String sourceEventId,
            String status,
            String failureClassification
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reservation_deposit_dispositions (
                    disposition_id, payment_pk, source_event_id, source_event_type,
                    corrects_source_event_id, policy_version, responsibility_code,
                    target_refund_rate_basis_points, original_amount_minor,
                    target_refund_amount_minor, incremental_refund_amount_minor,
                    completed_refund_amount_minor, withheld_amount_minor, currency,
                    idempotency_key, request_fingerprint, status, failure_classification,
                    attempt_count, requested_at, updated_at, version
                ) SELECT ?, payment_pk, ?, 'RESERVATION_CANCELLATION_CORRECTED',
                         'reservation:123:no-refund', 7, 'CONSUMER',
                         5000, 30000, 15000, 15000, 0, 15000, 'KRW',
                         ?, REPEAT('d', 64), ?, ?,
                         CASE WHEN ? = 'PROCESSING' THEN 1 ELSE 0 END,
                         NOW(6), NOW(6), 0
                    FROM payments WHERE payment_id = '900000000000000001'
                """)) {
            statement.setString(1, dispositionId);
            statement.setString(2, sourceEventId);
            statement.setString(3, dispositionId);
            statement.setString(4, status);
            statement.setString(5, failureClassification);
            statement.setString(6, status);
            statement.executeUpdate();
        }
    }
}
