package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.contract.ReservationMonitoringContracts;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false"
        })
class ReservationMonitoringRepositoryIT {

    private static final Instant CHANGED_AT = Instant.parse("2026-08-19T06:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReservationMonitoringQueryService service;

    @Test
    void appliesStoreStatusAndPublicSeekBeforeLimitWithEqualTimestamps() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            List<Object[]> reservations = new ArrayList<>();
            for (int index = 0; index < 101; index++) {
                reservations.add(reservation(600_000L + index, 12L));
            }
            reservations.add(reservation(699_999L, 13L));
            jdbcTemplate.batchUpdate(insertSql(), reservations);
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        ReservationMonitoringContracts.ChangeQuery query =
                new ReservationMonitoringContracts.ChangeQuery(
                        CHANGED_AT.plusSeconds(1), CHANGED_AT, CHANGED_AT,
                        "12", Set.of("CONFIRMED"), null, 100);
        ReservationMonitoringContracts.ReferencePage first = service.findChangedCases(query);
        ReservationMonitoringContracts.CaseReference last = first.items().getLast();
        ReservationMonitoringContracts.ReferencePage second = service.findChangedCases(
                new ReservationMonitoringContracts.ChangeQuery(
                        query.asOf(), query.changedFrom(), query.changedTo(), query.storeId(),
                        query.sourceStatuses(), new ReservationMonitoringContracts.Seek(
                                last.statusChangedAt(), last.caseId()), 100));

        assertThat(first.items()).hasSize(100);
        assertThat(second.items()).hasSize(1);
        assertThat(first.items()).extracting(ReservationMonitoringContracts.CaseReference::caseId)
                .doesNotContain("reservation:699999");
        assertThat(second.items()).extracting(ReservationMonitoringContracts.CaseReference::caseId)
                .doesNotContain("reservation:699999");
    }

    private static String insertSql() {
        return """
                INSERT INTO reservations (
                    reservation_id, consumer_account_id, store_id, store_name_snapshot,
                    service_date, start_time, end_time,
                    adult_count, child_count, infant_count,
                    notification_target_reference, contact_available_at_confirmation,
                    capacity_policy_version, reservation_policy_version,
                    cancellation_policy_version, status, created_at
                ) VALUES (?, 1, ?, 'monitoring store', '2026-08-20',
                          '12:00:00', '13:00:00', 2, 0, 0,
                          'opaque-monitoring-target', TRUE, 1, 1, 1,
                          'CONFIRMED', ?)
                """;
    }

    private static Object[] reservation(long reservationId, long storeId) {
        return new Object[]{reservationId, storeId, dbTimestamp(CHANGED_AT)};
    }

    private static Timestamp dbTimestamp(Instant value) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }
}
