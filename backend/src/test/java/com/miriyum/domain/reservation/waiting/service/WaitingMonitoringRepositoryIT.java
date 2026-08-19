package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.waiting.dto.WaitingMonitoringContracts;
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
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class WaitingMonitoringRepositoryIT {

    private static final Instant CHANGED_AT = Instant.parse("2026-08-19T06:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WaitingMonitoringQueryService service;

    @Test
    void appliesStoreStatusAndPublicSeekBeforeLimitWithEqualTimestamps() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            List<Object[]> teams = new ArrayList<>();
            List<Object[]> audits = new ArrayList<>();
            for (int index = 0; index < 101; index++) {
                long teamId = 800_000L + index;
                teams.add(team(teamId, 12L, index + 1L));
                audits.add(audit(teamId, "WAITING"));
            }
            teams.add(team(899_999L, 13L, 200L));
            audits.add(audit(899_999L, "WAITING"));
            jdbcTemplate.batchUpdate(insertTeamSql(), teams);
            jdbcTemplate.batchUpdate(insertAuditSql(), audits);
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        WaitingMonitoringContracts.ChangeQuery query =
                new WaitingMonitoringContracts.ChangeQuery(
                        CHANGED_AT.plusSeconds(1), CHANGED_AT, CHANGED_AT,
                        "12", Set.of("WAITING"), null, 100);
        WaitingMonitoringContracts.ReferencePage first = service.findChangedCases(query);
        WaitingMonitoringContracts.CaseReference last = first.items().getLast();
        WaitingMonitoringContracts.ReferencePage second = service.findChangedCases(
                new WaitingMonitoringContracts.ChangeQuery(
                        query.asOf(), query.changedFrom(), query.changedTo(), query.storeId(),
                        query.sourceStatuses(), new WaitingMonitoringContracts.Seek(
                                last.statusChangedAt(), last.caseId()), 100));

        assertThat(first.items()).hasSize(100);
        assertThat(second.items()).hasSize(1);
        assertThat(first.items()).extracting(WaitingMonitoringContracts.CaseReference::caseId)
                .doesNotContain("waiting:899999");
        assertThat(second.items()).extracting(WaitingMonitoringContracts.CaseReference::caseId)
                .doesNotContain("waiting:899999");
    }

    private static String insertTeamSql() {
        return """
                INSERT INTO waiting_teams (
                    waiting_team_id, store_id, consumer_account_id, business_date,
                    party_size, source, queue_sequence, status, version, created_at
                ) VALUES (?, ?, 1, '2026-08-19', 2, 'REMOTE', ?, 'WAITING', 0, ?)
                """;
    }

    private static Object[] team(long teamId, long storeId, long sequence) {
        return new Object[]{teamId, storeId, sequence, dbTimestamp(CHANGED_AT.minusSeconds(60))};
    }

    private static String insertAuditSql() {
        return """
                INSERT INTO waiting_transition_audits (
                    waiting_team_id, actor_type, actor_id, before_status, after_status,
                    expected_version, result_version, reason, command_id, occurred_at, created_at
                ) VALUES (?, 'SYSTEM', NULL, NULL, ?, -1, 0, 'monitoring fixture', ?, ?, ?)
                """;
    }

    private static Object[] audit(long teamId, String status) {
        return new Object[]{
                teamId, status, "monitoring-waiting-" + teamId,
                dbTimestamp(CHANGED_AT), dbTimestamp(CHANGED_AT)
        };
    }

    private static Timestamp dbTimestamp(Instant value) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }
}
