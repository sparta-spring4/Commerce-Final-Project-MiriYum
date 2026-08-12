package com.miriyum.domain.reservation.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
            "miriyum.reservation.time-policy.activation-enabled=false"
        })
class WaitingMigrationTest {

    private static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0.40");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Test
    @DisplayName("Flyway V36이 웨이팅 원장 마이그레이션을 적용한다")
    void appliesWaitingLedgerAsFlywayV36() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();

        flyway.migrate();

        assertThat(flyway.info().applied())
                .anyMatch(migration ->
                        "36".equals(String.valueOf(migration.getVersion()))
                                && "V36__create_waiting_ledger.sql"
                                .equals(migration.getScript()));
    }

    @Test
    @DisplayName("V36은 웨이팅 원장 소유 테이블 일곱 개만 생성한다")
    void createsExactWaitingLedgerTableSet() throws SQLException {
        migrate();

        assertThat(waitingTables()).containsExactly(
                "waiting_active_memberships",
                "waiting_closure_job_items",
                "waiting_closure_jobs",
                "waiting_queue_sequences",
                "waiting_status_events",
                "waiting_teams",
                "waiting_transition_audits"
        );
    }

    @Test
    @DisplayName("웨이팅 원장 FK는 소비자 매장 팀 종결 작업 원본을 정확히 참조한다")
    void createsApprovedForeignKeyTargets() throws SQLException {
        migrate();

        assertThat(foreignKeyTargets()).containsExactlyInAnyOrder(
                "waiting_active_memberships.consumer_account_id->consumer_accounts.consumer_account_id",
                "waiting_active_memberships.store_id->stores.store_id",
                "waiting_active_memberships.waiting_team_id->waiting_teams.waiting_team_id",
                "waiting_closure_job_items.waiting_closure_job_id->waiting_closure_jobs.waiting_closure_job_id",
                "waiting_closure_job_items.waiting_team_id->waiting_teams.waiting_team_id",
                "waiting_closure_jobs.store_id->stores.store_id",
                "waiting_queue_sequences.store_id->stores.store_id",
                "waiting_status_events.waiting_team_id->waiting_teams.waiting_team_id",
                "waiting_teams.consumer_account_id->consumer_accounts.consumer_account_id",
                "waiting_teams.store_id->stores.store_id",
                "waiting_transition_audits.waiting_team_id->waiting_teams.waiting_team_id"
        );
    }

    @Test
    @DisplayName("활성 중복 순번 감사 명령과 종결 작업 항목의 중앙 유일 키를 만든다")
    void createsRequiredUniqueKeys() throws SQLException {
        migrate();

        assertThat(uniqueIndexColumns()).contains(
                "waiting_active_memberships.uk_waiting_active_memberships_store_consumer="
                        + "store_id,consumer_account_id",
                "waiting_active_memberships.uk_waiting_active_memberships_team=waiting_team_id",
                "waiting_closure_job_items.uk_waiting_closure_job_items_job_team="
                        + "waiting_closure_job_id,waiting_team_id",
                "waiting_closure_jobs.uk_waiting_closure_jobs_store_settings="
                        + "store_id,settings_version",
                "waiting_teams.uk_waiting_teams_store_date_sequence="
                        + "store_id,business_date,queue_sequence",
                "waiting_status_events.uk_waiting_status_events_team_sequence="
                        + "waiting_team_id,event_sequence",
                "waiting_transition_audits.uk_waiting_transition_audits_command=command_id"
        );
    }

    @Test
    @DisplayName("팀과 종결 작업 상태는 승인된 값으로 DB에서도 제한한다")
    void createsExactStatusChecks() throws SQLException {
        migrate();

        assertThat(checkClause("ck_waiting_teams_status"))
                .contains("WAITING", "CALLED", "ARRIVED", "CHECKED_IN", "CANCELLED")
                .contains("NO_SHOW", "CLOSED_BY_STORE", "RESERVATION_CONVERTING");
        assertThat(checkClause("ck_waiting_closure_jobs_status"))
                .contains("PENDING", "PROCESSING", "COMPLETED", "RECONCILIATION_REQUIRED");
        assertThat(checkClause("ck_waiting_closure_job_items_status"))
                .contains("PENDING", "PROCESSING", "COMPLETED", "FAILED")
                .contains("RECONCILIATION_REQUIRED");
        assertThat(checkClause("ck_waiting_status_events_public_status"))
                .contains("WAITING", "CALLED", "ARRIVED", "CHECKED_IN", "CANCELLED")
                .contains("NO_SHOW", "CLOSED_BY_STORE", "RESERVATION_CONVERTING");
    }

    @Test
    @DisplayName("FIFO 조회 감사 조회 종결 작업과 항목 claim에 필요한 인덱스를 만든다")
    void createsRequiredOperationalIndexes() throws SQLException {
        migrate();

        assertThat(nonUniqueIndexColumns()).contains(
                "waiting_closure_job_items.idx_waiting_closure_job_items_claim="
                        + "waiting_closure_job_id,status,waiting_closure_job_item_id",
                "waiting_closure_jobs.idx_waiting_closure_jobs_store="
                        + "store_id,created_at,waiting_closure_job_id",
                "waiting_closure_jobs.idx_waiting_closure_jobs_worker="
                        + "status,created_at,waiting_closure_job_id",
                "waiting_status_events.idx_waiting_status_events_publication="
                        + "publication_state,waiting_status_event_id",
                "waiting_teams.idx_waiting_teams_consumer_history="
                        + "consumer_account_id,created_at,waiting_team_id",
                "waiting_teams.idx_waiting_teams_fifo="
                        + "store_id,business_date,status,queue_sequence,waiting_team_id",
                "waiting_transition_audits.idx_waiting_transition_audits_team="
                        + "waiting_team_id,waiting_transition_audit_id"
        );
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    private List<String> waitingTables() throws SQLException {
        return queryStrings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name LIKE 'waiting\\_%'
                ORDER BY table_name
                """);
    }

    private List<String> foreignKeyTargets() throws SQLException {
        return queryStrings("""
                SELECT CONCAT(
                    kcu.table_name, '.', kcu.column_name, '->',
                    kcu.referenced_table_name, '.', kcu.referenced_column_name
                )
                FROM information_schema.key_column_usage kcu
                WHERE kcu.constraint_schema = DATABASE()
                  AND kcu.table_name LIKE 'waiting\\_%'
                  AND kcu.referenced_table_name IS NOT NULL
                ORDER BY kcu.table_name, kcu.constraint_name, kcu.ordinal_position
                """);
    }

    private List<String> uniqueIndexColumns() throws SQLException {
        return queryStrings("""
                SELECT CONCAT(
                    table_name, '.', index_name, '=',
                    GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                )
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name LIKE 'waiting\\_%'
                  AND non_unique = 0
                  AND index_name <> 'PRIMARY'
                GROUP BY table_name, index_name
                ORDER BY table_name, index_name
                """);
    }

    private List<String> nonUniqueIndexColumns() throws SQLException {
        return queryStrings("""
                SELECT CONCAT(
                    table_name, '.', index_name, '=',
                    GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                )
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name LIKE 'waiting\\_%'
                  AND non_unique = 1
                GROUP BY table_name, index_name
                ORDER BY table_name, index_name
                """);
    }

    private String checkClause(String constraintName) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT check_clause
                     FROM information_schema.check_constraints
                     WHERE constraint_schema = DATABASE()
                       AND constraint_name = ?
                     """)) {
            statement.setString(1, constraintName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("check constraint %s", constraintName).isTrue();
                return rows.getString("check_clause");
            }
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
    }
}
