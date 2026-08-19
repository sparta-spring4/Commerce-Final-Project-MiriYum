package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.menuhold.dto.MenuHoldMonitoringContracts;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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
            "miriyum.reservation.hold-expiration.enabled=false"
        })
class MenuHoldMonitoringRepositoryIT {

    private static final Instant CHANGED_AT = Instant.parse("2026-08-19T06:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MenuHoldMonitoringQueryService service;

    @Test
    @Transactional
    void appliesStoreStatusAndPublicSeekBeforeLimitWithEqualTimestamps() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            List<Object[]> holds = new ArrayList<>();
            List<Object[]> audits = new ArrayList<>();
            for (int index = 0; index < 101; index++) {
                long holdId = 700_000L + index;
                long reservationId = 900_000L + index;
                holds.add(hold(holdId, reservationId, 12L));
                audits.add(audit(holdId, reservationId, "CONFIRMED"));
            }
            holds.add(hold(799_999L, 999_999L, 13L));
            audits.add(audit(799_999L, 999_999L, "CONFIRMED"));
            jdbcTemplate.batchUpdate(insertHoldSql(), holds);
            jdbcTemplate.batchUpdate(insertAuditSql(), audits);
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        MenuHoldMonitoringContracts.ChangeQuery query =
                new MenuHoldMonitoringContracts.ChangeQuery(
                        CHANGED_AT.plusSeconds(1), CHANGED_AT, CHANGED_AT,
                        "12", Set.of("CONFIRMED"), null, 100);
        MenuHoldMonitoringContracts.ReferencePage first = service.findChangedCases(query);
        MenuHoldMonitoringContracts.CaseReference last = first.items().getLast();
        MenuHoldMonitoringContracts.ReferencePage second = service.findChangedCases(
                new MenuHoldMonitoringContracts.ChangeQuery(
                        query.asOf(), query.changedFrom(), query.changedTo(), query.storeId(),
                        query.sourceStatuses(), new MenuHoldMonitoringContracts.Seek(
                                last.statusChangedAt(), last.caseId()), 100));

        assertThat(first.items()).hasSize(100);
        assertThat(second.items()).hasSize(1);
        assertThat(first.items()).extracting(MenuHoldMonitoringContracts.CaseReference::caseId)
                .doesNotContain("reservation:999999");
        assertThat(second.items()).extracting(MenuHoldMonitoringContracts.CaseReference::caseId)
                .doesNotContain("reservation:999999");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE menu_hold_transition_audits
                   SET after_status = 'RELEASED'
                 WHERE menu_hold_id = 700000
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("menu hold transition audits are immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM menu_hold_transition_audits WHERE menu_hold_id = 700000
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("menu hold transition audits are immutable");
    }

    private static String insertHoldSql() {
        return """
                INSERT INTO menu_holds (
                    menu_hold_id, reservation_id, store_id, consumer_account_id,
                    service_date, start_time, end_date, end_time,
                    acquire_operation_id, status, status_version, created_at, updated_at
                ) VALUES (?, ?, ?, 1, '2026-08-20', '12:00:00', '2026-08-20',
                          '13:00:00', ?, 'CONFIRMED', 0, ?, ?)
                """;
    }

    private static Object[] hold(long holdId, long reservationId, long storeId) {
        return new Object[]{
                holdId, reservationId, storeId, "monitoring-menu-hold-" + holdId,
                dbTimestamp(CHANGED_AT.minusSeconds(60)),
                dbTimestamp(CHANGED_AT.minusSeconds(60))
        };
    }

    private static String insertAuditSql() {
        return """
                INSERT INTO menu_hold_transition_audits (
                    menu_hold_id, reservation_id, reservation_hold_id, event_type,
                    before_status, after_status, result_version, occurred_at
                ) VALUES (?, ?, NULL, 'CREATED', NULL, ?, 0, ?)
                """;
    }

    private static Object[] audit(long holdId, long reservationId, String status) {
        return new Object[]{holdId, reservationId, status, dbTimestamp(CHANGED_AT)};
    }

    private static Timestamp dbTimestamp(Instant value) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }
}
