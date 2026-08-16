package com.miriyum.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
class ReservationDepositMigrationTest {

    private static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0.40");

    @Test
    void upgradesLegacyReservationAndPaymentWithoutBackfill() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MYSQL_IMAGE)
                .withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .target(MigrationVersion.fromVersion("48"))
                    .load()
                    .migrate();
            seedLegacyReservationAndPayment(mysql);

            String reservationBefore;
            String paymentBefore;
            try (Connection connection = mysql.createConnection("")) {
                reservationBefore = singleString(connection, """
                        SELECT JSON_OBJECT(
                            'reservationId', reservation_id,
                            'consumerAccountId', consumer_account_id,
                            'storeId', store_id,
                            'status', status,
                            'createdAt', created_at
                        )
                        FROM reservations
                        WHERE reservation_id = 40001
                        """);
                paymentBefore = singleString(connection, """
                        SELECT JSON_OBJECT(
                            'paymentId', payment_id,
                            'sourceType', source_type,
                            'sourceReferenceId', source_reference_id,
                            'amountMinor', amount_minor,
                            'status', status,
                            'version', version
                        )
                        FROM payments
                        WHERE payment_id = '900000000000000001'
                        """);
            }

            Flyway upgraded = Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .load();
            upgraded.migrate();

            assertThat(upgraded.info().applied())
                    .extracting(MigrationInfo::getScript)
                    .contains("V52__create_reservation_deposit_runtime.sql");
            try (Connection connection = mysql.createConnection("")) {
                assertThat(singleString(connection, """
                        SELECT JSON_OBJECT(
                            'reservationId', reservation_id,
                            'consumerAccountId', consumer_account_id,
                            'storeId', store_id,
                            'status', status,
                            'createdAt', created_at
                        )
                        FROM reservations
                        WHERE reservation_id = 40001
                        """)).isEqualTo(reservationBefore);
                assertThat(singleString(connection, """
                        SELECT JSON_OBJECT(
                            'paymentId', payment_id,
                            'sourceType', source_type,
                            'sourceReferenceId', source_reference_id,
                            'amountMinor', amount_minor,
                            'status', status,
                            'version', version
                        )
                        FROM payments
                        WHERE payment_id = '900000000000000001'
                        """)).isEqualTo(paymentBefore);
                assertThat(singleLong(connection,
                        "SELECT COUNT(*) FROM reservation_deposit_processes")).isZero();
                assertThat(singleLong(connection,
                        "SELECT COUNT(*) FROM reservation_deposit_calculation_items")).isZero();
                assertThat(singleLong(connection,
                        "SELECT COUNT(*) FROM reservation_deposit_cause_audits")).isZero();
                assertThat(singleLong(connection,
                        "SELECT COUNT(*) FROM reservation_deposit_refund_obligations")).isZero();
                assertThat(columnsOf(connection, "reservation_deposit_processes")).contains(
                        "resources_protected",
                        "resources_protected_at",
                        "reconciliation_next_attempt_at",
                        "reconciliation_lease_owner",
                        "reconciliation_lease_until",
                        "reconciliation_claim_token",
                        "reconciliation_last_attempted_at");
                assertThat(columnsOf(
                        connection,
                        "reservation_deposit_refund_obligations")).contains(
                        "next_attempt_at",
                        "lease_owner",
                        "lease_until",
                        "claim_token",
                        "last_attempted_at");
                assertDepositConstraintsAndIndexes(connection);
            }
        }
    }

    private static void assertDepositConstraintsAndIndexes(Connection connection)
            throws Exception {
        assertThat(indexesOf(connection, "reservation_deposit_processes")).contains(
                "uk_reservation_deposit_process_hold",
                "uk_reservation_deposit_process_payment",
                "uk_reservation_deposit_process_final_reservation",
                "idx_reservation_deposit_process_reconciliation_due");
        assertThat(indexesOf(connection, "reservation_deposit_refund_obligations")).contains(
                "uk_reservation_deposit_refund_obligation_identity",
                "idx_reservation_deposit_refund_due");
        assertThat(referencedTablesOf(connection, "reservation_deposit_processes"))
                .contains("reservation_holds", "consumer_accounts", "reservations")
                .doesNotContain("payments");

        long firstProcessId = insertAwaitingProcess(
                connection, 40001L, "deposit-unbacked-payment-1");
        long secondProcessId = insertAwaitingProcess(
                connection, 40002L, "deposit-unbacked-payment-2");
        assertThat(firstProcessId).isPositive();
        assertThat(secondProcessId).isPositive();

        assertSqlFails(connection, """
                INSERT INTO reservation_deposit_processes (
                    reservation_hold_id, consumer_account_id, status, expires_at,
                    payment_id, portone_payment_id, payment_order_name,
                    payment_amount_minor, payment_currency, payment_source_expires_at,
                    payment_preparation_status, store_deposit_policy_version,
                    deposit_rate_percent, deposit_algorithm_version, deposit_party_size,
                    deposit_amount_minor, deposit_currency, representative_menu_version,
                    representative_menu_price_total, representative_menu_count,
                    abandonment_requested, resources_protected, requested_at,
                    reconciliation_next_attempt_at
                )
                SELECT reservation_hold_id, consumer_account_id, status, expires_at,
                    'deposit-duplicate-hold', 'duplicate-portone', payment_order_name,
                    payment_amount_minor, payment_currency, payment_source_expires_at,
                    payment_preparation_status, store_deposit_policy_version,
                    deposit_rate_percent, deposit_algorithm_version, deposit_party_size,
                    deposit_amount_minor, deposit_currency, representative_menu_version,
                    representative_menu_price_total, representative_menu_count,
                    abandonment_requested, resources_protected, requested_at,
                    reconciliation_next_attempt_at
                FROM reservation_deposit_processes
                WHERE reservation_deposit_process_id = %d
                """.formatted(firstProcessId));
        assertSqlFails(connection, """
                UPDATE reservation_deposit_processes
                SET resources_protected = TRUE
                WHERE reservation_deposit_process_id = %d
                """.formatted(secondProcessId));

        executeUpdate(connection, """
                UPDATE reservation_deposit_processes
                SET status = 'COMPLETED',
                    final_reservation_id = 40001,
                    completed_at = '2026-08-20 09:05:00.000000'
                WHERE reservation_deposit_process_id = %d
                """.formatted(firstProcessId));
        assertSqlFails(connection, """
                UPDATE reservation_deposit_processes
                SET status = 'COMPLETED',
                    final_reservation_id = 40001,
                    completed_at = '2026-08-20 09:06:00.000000'
                WHERE reservation_deposit_process_id = %d
                """.formatted(secondProcessId));

        executeUpdate(connection, """
                INSERT INTO reservation_deposit_cause_audits (
                    reservation_deposit_process_id, cause_code, payment_id,
                    payment_status, paid_at, observed_at
                ) VALUES (
                    %d, 'LATE_PAID', 'deposit-unbacked-payment-2',
                    'PAID', '2026-08-20 09:11:00.000000',
                    '2026-08-20 09:11:01.000000'
                )
                """.formatted(secondProcessId));
        assertSqlFails(connection, """
                INSERT INTO reservation_deposit_cause_audits (
                    reservation_deposit_process_id, cause_code, payment_id,
                    payment_status, paid_at, observed_at
                ) VALUES (
                    %d, 'LATE_PAID', 'deposit-unbacked-payment-2',
                    'PAID', '2026-08-20 09:11:00.000000',
                    '2026-08-20 09:11:02.000000'
                )
                """.formatted(secondProcessId));

        executeUpdate(connection, """
                INSERT INTO reservation_deposit_refund_obligations (
                    reservation_deposit_process_id, payment_id, refund_amount_minor,
                    currency, refund_policy_version, source_event_id, idempotency_key,
                    reason_code, status, attempt_count, next_attempt_at, claim_token,
                    created_at
                ) VALUES (
                    %d, 'deposit-unbacked-payment-2', 4000, 'KRW', 1,
                    'late-paid:2', '550e8400-e29b-41d4-a716-446655440002',
                    'FULL_DEPOSIT_COMPENSATION', 'REQUIRED', 0,
                    '2026-08-20 09:11:01.000000', 0,
                    '2026-08-20 09:11:01.000000'
                )
                """.formatted(secondProcessId));
        assertSqlFails(connection, """
                INSERT INTO reservation_deposit_refund_obligations (
                    reservation_deposit_process_id, payment_id, refund_amount_minor,
                    currency, refund_policy_version, source_event_id, idempotency_key,
                    reason_code, status, attempt_count, next_attempt_at, claim_token,
                    created_at
                ) VALUES (
                    %d, 'deposit-unbacked-payment-2', 4000, 'KRW', 1,
                    'late-paid:duplicate', '550e8400-e29b-41d4-a716-446655440003',
                    'FULL_DEPOSIT_COMPENSATION', 'REQUIRED', 0,
                    '2026-08-20 09:11:02.000000', 0,
                    '2026-08-20 09:11:02.000000'
                )
                """.formatted(secondProcessId));
    }

    private static long insertAwaitingProcess(
            Connection connection,
            long holdId,
            String paymentId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reservation_deposit_processes (
                    reservation_hold_id, consumer_account_id, status, expires_at,
                    payment_id, portone_payment_id, payment_order_name,
                    payment_amount_minor, payment_currency, payment_source_expires_at,
                    payment_preparation_status, store_deposit_policy_version,
                    deposit_rate_percent, deposit_algorithm_version, deposit_party_size,
                    deposit_amount_minor, deposit_currency, representative_menu_version,
                    representative_menu_price_total, representative_menu_count,
                    abandonment_requested, resources_protected, requested_at,
                    reconciliation_next_attempt_at
                ) VALUES (
                    ?, 10001, 'AWAITING_PAYMENT', '2026-08-20 09:10:00.000000',
                    ?, CONCAT('portone-', ?), '예약금 스키마 검증',
                    4000, 'KRW', '2026-08-20 09:10:00.000000',
                    'READY', 91, 20, 1, 2, 4000, 'KRW', 13, 40000, 2,
                    FALSE, FALSE, '2026-08-20 09:00:00.000000',
                    '2026-08-20 09:00:00.000000'
                )
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, holdId);
            statement.setString(2, paymentId);
            statement.setString(3, paymentId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                assertThat(generatedKeys.next()).isTrue();
                return generatedKeys.getLong(1);
            }
        }
    }

    private static void assertSqlFails(Connection connection, String sql) {
        assertThatThrownBy(() -> executeUpdate(connection, sql))
                .isInstanceOf(SQLException.class);
    }

    private static void executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void seedLegacyReservationAndPayment(MySQLContainer mysql)
            throws Exception {
        try (Connection connection = mysql.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO consumer_accounts (
                        consumer_account_id, email, password_hash, name,
                        status, created_at, updated_at
                    ) VALUES (
                        10001, 'deposit-migration@example.com', 'hash', '예약금 회원',
                        'ACTIVE', NOW(6), NOW(6)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO store_operator_accounts (
                        store_operator_account_id, email, password_hash, display_name,
                        status, created_at, updated_at
                    ) VALUES (
                        20001, 'deposit-owner@example.com', 'hash', '예약금 운영자',
                        'ACTIVE', NOW(6), NOW(6)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO stores (
                        store_id, store_operator_account_id, business_registration_number,
                        business_type, name, description, region, address, time_zone_id,
                        applicant_self_attested_at, required_terms_agreed_at,
                        required_terms_version, store_category_code, verification_status,
                        operation_status, reservation_enabled, menu_hold_enabled,
                        pickup_enabled, created_at, updated_at
                    ) VALUES (
                        30001, 20001, '9876543210', 'CAFE', '예약금 기존 매장', '',
                        'SEOUL', '서울시 중구', 'Asia/Seoul', NOW(6), NOW(6),
                        'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY',
                        'APPROVED', 'OPEN', TRUE, TRUE, TRUE, NOW(6), NOW(6)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO reservations (
                        reservation_id, consumer_account_id, store_id,
                        store_name_snapshot, service_date, start_time, end_time,
                        adult_count, child_count, infant_count,
                        notification_target_reference,
                        contact_available_at_confirmation,
                        capacity_policy_version, reservation_policy_version,
                        status, created_at
                    ) VALUES (
                        40001, 10001, 30001, '예약금 기존 매장', '2026-08-20',
                        '18:00:00.000000', '19:00:00.000000', 2, 1, 0,
                        'consumer:10001:channel:primary', TRUE, 7, 9,
                        'CONFIRMED', '2026-08-01 01:00:00.000000'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO payments (
                        payment_id, source_type, source_reference_id,
                        source_policy_version, source_expires_at,
                        preparation_idempotency_key, preparation_request_fingerprint,
                        consumer_account_id, amount_minor, refunded_amount_minor,
                        currency, portone_payment_id, order_name,
                        status, last_attempt_status, created_at, updated_at, version
                    ) VALUES (
                        '900000000000000001', 'RESERVATION_DEPOSIT', '40001', 1,
                        '2026-08-20 09:10:00.000000',
                        '550e8400-e29b-41d4-a716-446655440000', REPEAT('a', 64),
                        10001, 4000, 0, 'KRW', 'deposit-migration-portone',
                        '예약금 기존 결제', 'READY', 'NOT_STARTED',
                        '2026-08-01 01:00:00.000000',
                        '2026-08-01 01:00:00.000000', 0
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO reservation_holds (
                        reservation_hold_id, consumer_account_id, store_id,
                        store_name_snapshot, service_date, start_at, service_end_at,
                        occupancy_end_at, time_zone_id_snapshot, start_offset_seconds,
                        service_end_offset_seconds, occupancy_end_offset_seconds,
                        slot_interval_minutes, service_duration_minutes,
                        turnover_duration_minutes, reservation_time_policy_store_id,
                        reservation_policy_version, adult_count, child_count, infant_count,
                        notification_target_reference, contact_available_at_confirmation,
                        capacity_policy_version, cancellation_policy_version, status,
                        status_version, creation_command_id, created_at, expires_at
                    ) VALUES
                    (
                        40001, 10001, 30001, '예약금 기존 매장', '2026-08-21',
                        '2026-08-21 09:00:00.000000', '2026-08-21 10:00:00.000000',
                        '2026-08-21 10:10:00.000000', 'Asia/Seoul', 32400, 32400,
                        32400, 10, 60, 10, 30001, 9, 2, 0, 0,
                        'consumer:10001:channel:primary', TRUE, 7, 8, 'ACTIVE', 0,
                        'deposit-migration-hold-1', '2026-08-20 09:00:00.000000',
                        '2026-08-20 09:10:00.000000'
                    ),
                    (
                        40002, 10001, 30001, '예약금 기존 매장', '2026-08-21',
                        '2026-08-21 11:00:00.000000', '2026-08-21 12:00:00.000000',
                        '2026-08-21 12:10:00.000000', 'Asia/Seoul', 32400, 32400,
                        32400, 10, 60, 10, 30001, 9, 2, 0, 0,
                        'consumer:10001:channel:primary', TRUE, 7, 8, 'ACTIVE', 0,
                        'deposit-migration-hold-2', '2026-08-20 09:00:00.000000',
                        '2026-08-20 09:10:00.000000'
                    )
                    """);
        }
    }

    private static String singleString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            String value = resultSet.getString(1);
            assertThat(resultSet.next()).isFalse();
            return value;
        }
    }

    private static long singleLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private static List<String> columnsOf(Connection connection, String table)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                ORDER BY ordinal_position
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
                return columns;
            }
        }
    }

    private static List<String> indexesOf(Connection connection, String table)
            throws Exception {
        return metadataNames(connection, """
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                ORDER BY index_name
                """, table);
    }

    private static List<String> referencedTablesOf(Connection connection, String table)
            throws Exception {
        return metadataNames(connection, """
                SELECT DISTINCT referenced_table_name
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND referenced_table_name IS NOT NULL
                ORDER BY referenced_table_name
                """, table);
    }

    private static List<String> metadataNames(
            Connection connection,
            String sql,
            String table
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
                return names;
            }
        }
    }
}
