package com.miriyum.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
class ReservationCheckInNoShowMigrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            DockerImageName.parse("mysql:8.0.40")
    ).withCommand("--log-bin-trust-function-creators=1");

    @Test
    @DisplayName("V49가 NO_SHOW와 QR grant·감사 제약을 실제 MySQL에 적용한다")
    void appliesV49AndEnforcesVisitContracts() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        flyway.migrate();

        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getScript))
                .contains("V49__add_reservation_check_in_no_show.sql");

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            insertLegacyReservation(statement, 70001L);
            insertLegacyReservation(statement, 70002L);
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");

            statement.executeUpdate("""
                    UPDATE reservations
                    SET status = 'NO_SHOW', no_show_at = '2026-08-16 01:05:00.000000'
                    WHERE reservation_id = 70001
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE reservations
                    SET no_show_at = NULL
                    WHERE reservation_id = 70001
                    """)).isInstanceOf(SQLException.class);

            insertGrant(statement, 70001L, "11", "2026-08-16 01:00:00.000000",
                    "2026-08-16 01:00:30.000000");
            assertThatThrownBy(() -> insertGrant(
                    statement,
                    70002L,
                    "22",
                    "2026-08-16 01:00:00.000000",
                    "2026-08-16 01:00:29.999999"
            )).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertGrant(
                    statement,
                    70001L,
                    "33",
                    "2026-08-16 01:00:00.000000",
                    "2026-08-16 01:00:30.000000"
            )).isInstanceOf(SQLException.class);
        }
    }

    private static void insertLegacyReservation(Statement statement, long reservationId)
            throws SQLException {
        statement.executeUpdate("""
                INSERT INTO reservations (
                    reservation_id, consumer_account_id, store_id, store_name_snapshot,
                    service_date, start_time, end_time, adult_count, child_count, infant_count,
                    notification_target_reference, contact_available_at_confirmation,
                    capacity_policy_version, reservation_policy_version,
                    cancellation_policy_version, status, created_at
                ) VALUES (
                    %d, 11, 22, '미리윰', '2026-08-16',
                    '10:00:00.000000', '11:00:00.000000', 2, 0, 0,
                    'consumer:11:channel:primary', TRUE, 1, 1, 1,
                    'CONFIRMED', '2026-08-16 00:00:00.000000'
                )
                """.formatted(reservationId));
    }

    private static void insertGrant(
            Statement statement,
            long reservationId,
            String digestHexByte,
            String issuedAt,
            String expiresAt
    ) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO reservation_check_in_qr_grants (
                    reservation_id, token_version, token_digest,
                    qr_epoch_account_id, qr_epoch_opaque_version,
                    issued_at, expires_at, consumed_at
                ) VALUES (
                    %d, 1, UNHEX(REPEAT('%s', 32)),
                    11, CONCAT('v1.', REPEAT('A', 43)),
                    '%s', '%s', NULL
                )
                """.formatted(reservationId, digestHexByte, issuedAt, expiresAt));
    }
}
