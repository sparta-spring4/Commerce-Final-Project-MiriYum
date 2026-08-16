package com.miriyum.domain.reservation.waiting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
class WaitingMigrationTest {

    private static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0.40");
    private static final Set<String> TEAM_STATUSES = Set.of(
            "WAITING",
            "CALLED",
            "ARRIVED",
            "CHECKED_IN",
            "CANCELLED",
            "NO_SHOW",
            "CLOSED_BY_STORE",
            "RESERVATION_CONVERTING",
            "RESERVATION_CONVERTED"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withCommand("--log-bin-trust-function-creators=1");

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
    @DisplayName("Flyway V45가 웨이팅 예약 전환 runtime 스키마를 적용한다")
    void appliesWaitingReservationConversionRuntimeAsFlywayV45() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();

        flyway.migrate();

        assertThat(flyway.info().applied())
                .anyMatch(migration ->
                        "45".equals(String.valueOf(migration.getVersion()))
                                && "V45__add_waiting_reservation_conversion_runtime.sql"
                                .equals(migration.getScript()));
    }

    @Test
    @DisplayName("V50까지 적용하면 Waiting 소유 테이블 열두 개만 존재한다")
    void createsExactWaitingLedgerTableSet() throws SQLException {
        migrate();

        assertThat(waitingTables()).containsExactly(
                "waiting_active_memberships",
                "waiting_auto_open_jobs",
                "waiting_closure_job_items",
                "waiting_closure_jobs",
                "waiting_conversion_compensations",
                "waiting_queue_sequences",
                "waiting_reception_windows",
                "waiting_setting_audits",
                "waiting_settings",
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
                "waiting_auto_open_jobs.store_id->stores.store_id",
                "waiting_closure_job_items.waiting_closure_job_id->waiting_closure_jobs.waiting_closure_job_id",
                "waiting_closure_job_items.waiting_team_id->waiting_teams.waiting_team_id",
                "waiting_closure_jobs.store_id->stores.store_id",
                "waiting_conversion_compensations.waiting_team_id->waiting_teams.waiting_team_id",
                "waiting_queue_sequences.store_id->stores.store_id",
                "waiting_reception_windows.opened_by_job_id->waiting_auto_open_jobs.waiting_auto_open_job_id",
                "waiting_reception_windows.store_id->stores.store_id",
                "waiting_setting_audits.store_id->stores.store_id",
                "waiting_settings.store_id->stores.store_id",
                "waiting_status_events.waiting_team_id->waiting_teams.waiting_team_id",
                "waiting_teams.consumer_account_id->consumer_accounts.consumer_account_id",
                "waiting_teams.store_id->stores.store_id",
                "waiting_transition_audits.waiting_team_id->waiting_teams.waiting_team_id"
        );
    }

    @Test
    @DisplayName("V45는 Waiting 소유 예약 전환 scalar 열만 추가하고 Reservation FK를 만들지 않는다")
    void addsWaitingOwnedReservationConversionScalarColumns() throws SQLException {
        migrate();

        assertThat(queryStrings("""
                SELECT CONCAT(column_name, ':', is_nullable, ':', column_type)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'waiting_teams'
                  AND column_name IN (
                      'reservation_converting_at',
                      'waiting_payment_id',
                      'reservation_reference_id',
                      'reservation_converted_at'
                  )
                ORDER BY column_name
                """)).containsExactly(
                "reservation_converted_at:YES:datetime(6)",
                "reservation_converting_at:YES:datetime(6)",
                "reservation_reference_id:YES:bigint",
                "waiting_payment_id:YES:varchar(19)"
        );
        assertThat(foreignKeyTargets()).noneMatch(value -> value.contains("reservation"));
    }

    @Test
    @DisplayName("계정 전체 활성 중복 순번 감사 명령과 종결 작업 항목의 중앙 유일 키를 만든다")
    void createsRequiredUniqueKeys() throws SQLException {
        migrate();

        assertThat(uniqueIndexColumns()).contains(
                "waiting_active_memberships.uk_waiting_active_memberships_consumer_account="
                        + "consumer_account_id",
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
        assertThat(uniqueIndexColumns()).doesNotContain(
                "waiting_active_memberships.uk_waiting_active_memberships_store_consumer="
                        + "store_id,consumer_account_id");
    }

    @Test
    @DisplayName("V36 기준선 뒤 V44가 계정 전체 활성 membership 유일 키로 교체한다")
    void replacesStoreScopedMembershipKeyAfterV36Baseline() throws SQLException {
        try {
            Flyway v36 = flywayForTarget("36");
            v36.clean();
            v36.migrate();
            assertThat(v36.info().applied()).anyMatch(migration ->
                    "36".equals(String.valueOf(migration.getVersion())));
            try (Connection connection = connection()) {
                assertThat(uniqueIndexColumns(connection)).contains(
                        "waiting_active_memberships.uk_waiting_active_memberships_store_consumer="
                                + "store_id,consumer_account_id");
            }

            Flyway v44 = flywayForTarget(null);
            v44.migrate();

            assertThat(v44.info().applied()).anyMatch(migration ->
                    "44".equals(String.valueOf(migration.getVersion()))
                            && "V44__enforce_account_wide_active_waiting.sql"
                            .equals(migration.getScript()));
            try (Connection connection = connection()) {
                assertThat(uniqueIndexColumns(connection)).contains(
                        "waiting_active_memberships.uk_waiting_active_memberships_consumer_account="
                                + "consumer_account_id");
                assertThat(uniqueIndexColumns(connection)).doesNotContain(
                        "waiting_active_memberships.uk_waiting_active_memberships_store_consumer="
                                + "store_id,consumer_account_id");
            }
        } finally {
            cleanDatabase();
        }
    }

    @Test
    @DisplayName("V44는 계정 중복 데이터가 있으면 원본 데이터와 V36 유일 키를 보존한 채 실패한다")
    void preservesV36DataAndConstraintWhenAccountWideMigrationFindsDuplicates() throws SQLException {
        try {
            Flyway v36 = flywayForTarget("36");
            v36.clean();
            v36.migrate();
            try (Connection connection = connection()) {
                connection.createStatement().execute("SET FOREIGN_KEY_CHECKS = 0");
                connection.createStatement().executeUpdate("""
                        INSERT INTO waiting_active_memberships (
                            store_id, consumer_account_id, waiting_team_id, created_at
                        ) VALUES
                            (101, 200, 1001, UTC_TIMESTAMP(6)),
                            (102, 200, 1002, UTC_TIMESTAMP(6))
                        """);
                connection.createStatement().execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            assertThatThrownBy(() -> flywayForTarget(null).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("V44__enforce_account_wide_active_waiting.sql");

            try (Connection connection = connection()) {
                assertThat(membershipCount(connection)).isEqualTo(2);
                assertThat(uniqueIndexColumns(connection)).contains(
                        "waiting_active_memberships.uk_waiting_active_memberships_store_consumer="
                                + "store_id,consumer_account_id");
                assertThat(uniqueIndexColumns(connection)).doesNotContain(
                        "waiting_active_memberships.uk_waiting_active_memberships_consumer_account="
                                + "consumer_account_id");
            }
        } finally {
            cleanDatabase();
        }
    }

    @Test
    @DisplayName("V45는 V44 예약 전환 중 행을 재작성 취소 삭제 없이 그대로 보존한다")
    void preservesLegacyReservationConvertingRowWhenApplyingV45() throws SQLException {
        try {
            Flyway v44 = flywayForTarget("44");
            v44.clean();
            v44.migrate();
            try (Connection connection = connection()) {
                connection.createStatement().execute("SET FOREIGN_KEY_CHECKS = 0");
                insertLegacyReservationConvertingTeam(connection);
                connection.createStatement().execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            Flyway v45 = flywayForTarget(null);
            v45.migrate();

            assertThat(queryStrings("""
                    SELECT CONCAT(
                        status, ':', version, ':',
                        reservation_converting_at IS NULL, ':',
                        waiting_payment_id IS NULL, ':',
                        reservation_reference_id IS NULL, ':',
                        reservation_converted_at IS NULL
                    )
                    FROM waiting_teams
                    WHERE store_id = 99001
                      AND queue_sequence = 901
                    """)).containsExactly("RESERVATION_CONVERTING:0:1:1:1:1");
        } finally {
            cleanDatabase();
        }
    }

    @Test
    @DisplayName("순번 행의 기본 키는 매장과 영업일 복합 키다")
    void createsStoreBusinessDateCompositeSequencePrimaryKey() throws SQLException {
        migrate();

        assertThat(primaryKeyColumns("waiting_queue_sequences"))
                .containsExactly("store_id", "business_date");
    }

    @Test
    @DisplayName("팀과 종결 작업 상태는 승인된 값으로 DB에서도 제한한다")
    void createsExactStatusChecks() throws SQLException {
        migrate();

        assertThat(quotedValues(checkClause("ck_waiting_teams_status")))
                .containsExactlyInAnyOrderElementsOf(TEAM_STATUSES);
        assertThat(quotedValues(checkClause("ck_waiting_closure_jobs_status")))
                .containsExactlyInAnyOrder(
                        "PENDING", "PROCESSING", "COMPLETED", "RECONCILIATION_REQUIRED"
                );
        assertThat(quotedValues(checkClause("ck_waiting_closure_job_items_status")))
                .containsExactlyInAnyOrder(
                        "PENDING", "PROCESSING", "COMPLETED", "FAILED",
                        "RECONCILIATION_REQUIRED"
                );
        assertThat(quotedValues(checkClause("ck_waiting_status_events_public_status")))
                .containsExactlyInAnyOrderElementsOf(TEAM_STATUSES);
    }

    @Test
    @DisplayName("V45는 예약 전환 상태별 scalar 필드 조합과 시간 순서를 강제한다")
    void enforcesReservationConversionFieldInvariants() throws SQLException {
        migrate();
        Instant createdAt = Instant.parse("2026-08-12T03:00:00Z");
        Instant convertingAt = createdAt.plusSeconds(10);
        Instant completedAt = createdAt.plusSeconds(20);

        try (Connection connection = connection()) {
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS = 0");
            connection.createStatement().execute(
                    "DELETE FROM waiting_teams WHERE store_id = 99001"
            );

            insertConversionTeam(connection, 101L, "WAITING", createdAt,
                    null, null, null, null, null, null);
            insertConversionTeam(connection, 102L, "RESERVATION_CONVERTING", createdAt,
                    convertingAt, "123456789", null, null, null, null);
            insertConversionTeam(connection, 113L, "RESERVATION_CONVERTING", createdAt,
                    null, null, null, null, null, null);
            insertConversionTeam(connection, 103L, "RESERVATION_CONVERTED", createdAt,
                    convertingAt, "123456789", 987L, completedAt, null, null);
            insertConversionTeam(connection, 104L, "CANCELLED", createdAt,
                    convertingAt, "123456789", null, null, completedAt, null);
            insertConversionTeam(connection, 105L, "CLOSED_BY_STORE", createdAt,
                    convertingAt, "123456789", null, null, null, completedAt);

            assertConversionSnapshotRejected(connection, 106L, "WAITING", createdAt,
                    convertingAt, "123456789", null, null, null, null);
            assertConversionSnapshotRejected(connection, 107L, "RESERVATION_CONVERTING",
                    createdAt, convertingAt, null, null, null, null, null);
            assertConversionSnapshotRejected(connection, 108L, "RESERVATION_CONVERTING",
                    createdAt, convertingAt, "0", null, null, null, null);
            assertConversionSnapshotRejected(connection, 109L, "RESERVATION_CONVERTED",
                    createdAt, convertingAt, "123456789", null, completedAt, null, null);
            assertConversionSnapshotRejected(connection, 110L, "RESERVATION_CONVERTED",
                    createdAt, convertingAt, "123456789", 0L, completedAt, null, null);
            assertConversionSnapshotRejected(connection, 111L, "RESERVATION_CONVERTED",
                    createdAt, convertingAt, "123456789", 987L,
                    convertingAt.minusSeconds(1), null, null);
            assertConversionSnapshotRejected(connection, 112L, "CANCELLED", createdAt,
                    convertingAt, "123456789", 987L, completedAt, completedAt, null);
        }
    }

    @Test
    @DisplayName("팀 상태는 호출과 도착 시각 snapshot의 정확한 시간 순서를 강제한다")
    void bindsTeamStatusToChronologicalCallSnapshot() throws SQLException {
        migrate();
        Instant createdAt = Instant.parse("2026-08-12T03:00:00Z");
        Instant calledAt = Instant.parse("2026-08-12T03:01:00Z");
        Instant deadline = Instant.parse("2026-08-12T03:11:00Z");
        Instant arrivedAt = Instant.parse("2026-08-12T03:02:00Z");

        try (Connection connection = connection()) {
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS = 0");
            connection.createStatement().execute(
                    "DELETE FROM waiting_teams WHERE store_id = 99001"
            );

            insertTeam(connection, 1L, "WAITING", createdAt, null, null, null,
                    null, null, null, null);
            insertTeam(connection, 2L, "CALLED", createdAt, calledAt, deadline, null,
                    null, null, null, null);
            insertTeam(connection, 3L, "ARRIVED", createdAt, calledAt, deadline, arrivedAt,
                    null, null, null, null);
            insertTeam(connection, 4L, "CANCELLED", createdAt, null, null, null,
                    null, createdAt.plusSeconds(30), null, null);
            insertTeam(connection, 5L, "CANCELLED", createdAt, calledAt, deadline, null,
                    null, calledAt.plusSeconds(30), null, null);
            insertTeam(connection, 6L, "CANCELLED", createdAt, calledAt, deadline, arrivedAt,
                    null, arrivedAt.plusSeconds(30), null, null);
            insertTeam(connection, 11L, "CHECKED_IN", createdAt, calledAt, deadline, arrivedAt,
                    arrivedAt.plusSeconds(30), null, null, null);
            insertTeam(connection, 12L, "NO_SHOW", createdAt, calledAt, deadline, null,
                    null, null, deadline, null);
            insertTeam(connection, 13L, "CLOSED_BY_STORE", createdAt, null, null, null,
                    null, null, null, createdAt.plusSeconds(30));
            insertTeam(connection, 14L, "CLOSED_BY_STORE", createdAt, calledAt, deadline, null,
                    null, null, null, calledAt.plusSeconds(30));
            insertTeam(connection, 15L, "CLOSED_BY_STORE", createdAt, calledAt, deadline, arrivedAt,
                    null, null, null, arrivedAt.plusSeconds(30));
            insertTeam(connection, 16L, "RESERVATION_CONVERTING", createdAt, null, null, null,
                    null, null, null, null);

            assertTeamSnapshotRejected(connection, 7L, "WAITING", createdAt,
                    calledAt, deadline, null);
            assertTeamSnapshotRejected(connection, 8L, "CALLED", createdAt,
                    null, null, null);
            assertTeamSnapshotRejected(connection, 9L, "ARRIVED", createdAt,
                    calledAt, deadline, calledAt.minusSeconds(1));
            assertTeamSnapshotRejected(connection, 10L, "ARRIVED", createdAt,
                    calledAt, deadline, deadline.plusSeconds(1));
            assertThatThrownBy(() -> insertTeam(
                    connection, 17L, "CHECKED_IN", createdAt, calledAt, deadline, null,
                    calledAt.plusSeconds(30), null, null, null
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_waiting_teams_call_window");
            assertThatThrownBy(() -> insertTeam(
                    connection, 18L, "CANCELLED", createdAt, calledAt, deadline, null,
                    null, calledAt.minusSeconds(1), null, null
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_waiting_teams_call_window");
            assertThatThrownBy(() -> insertTeam(
                    connection, 19L, "NO_SHOW", createdAt, calledAt, deadline, null,
                    null, null, deadline.minusSeconds(1), null
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_waiting_teams_call_window");
            assertThatThrownBy(() -> insertTeam(
                    connection, 20L, "CLOSED_BY_STORE", createdAt, calledAt, deadline, arrivedAt,
                    null, null, null, arrivedAt.minusSeconds(1)
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_waiting_teams_call_window");
            assertTeamSnapshotRejected(connection, 21L, "RESERVATION_CONVERTING", createdAt,
                    calledAt, deadline, null);
        }
    }

    @Test
    @DisplayName("감사 원장은 생성 -1에서 0과 일반 전이의 단일 버전 증가를 구분한다")
    void enforcesCreationAndTransitionAuditVersionConventions() throws SQLException {
        migrate();
        Instant createdAt = Instant.parse("2026-08-12T03:00:00Z");

        try (Connection connection = connection()) {
            connection.createStatement().execute("SET FOREIGN_KEY_CHECKS = 0");
            insertAudit(connection, "creation-audit", null, "WAITING", -1L, 0L,
                    createdAt, createdAt);
            insertAudit(connection, "transition-audit", "WAITING", "CALLED", 0L, 1L,
                    createdAt, createdAt.plusSeconds(60));

            assertThatThrownBy(() -> insertAudit(
                    connection, "invalid-creation-audit", null, "WAITING", 0L, 1L,
                    createdAt, createdAt
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_waiting_transition_audits_versions");
            assertThatThrownBy(() -> insertAudit(
                    connection, "invalid-transition-audit", "WAITING", "CALLED", -1L, 0L,
                    createdAt, createdAt.plusSeconds(60)
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_waiting_transition_audits_versions");
            assertThatThrownBy(() -> insertAudit(
                    connection, "future-audit-time", "WAITING", "CALLED", 0L, 1L,
                    createdAt.plusSeconds(1), createdAt
            )).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_waiting_transition_audits_time");
        }
    }

    @Test
    @DisplayName("FIFO 조회 감사 조회 종결 작업과 항목 claim에 필요한 인덱스를 만든다")
    void createsRequiredOperationalIndexes() throws SQLException {
        migrate();

        assertThat(nonUniqueIndexColumns()).contains(
                "waiting_active_memberships.idx_waiting_active_memberships_store=store_id",
                "waiting_closure_job_items.idx_waiting_closure_job_items_claim="
                        + "waiting_closure_job_id,status,waiting_closure_job_item_id",
                "waiting_closure_job_items.idx_waiting_closure_job_items_global_claim="
                        + "status,lease_until,waiting_closure_job_item_id",
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

    @Test
    void closureItemsHaveDurableLeaseAndFencingColumns() throws SQLException {
        migrate();
        assertThat(queryStrings("""
                SELECT CONCAT(column_name, ':', is_nullable, ':', data_type)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'waiting_closure_job_items'
                  AND column_name IN ('lease_owner','lease_until','claim_token')
                ORDER BY column_name
                """)).containsExactly(
                "claim_token:NO:bigint", "lease_owner:YES:varchar", "lease_until:YES:datetime");
        assertThat(checkClause("ck_waiting_closure_job_items_claim_token"))
                .contains("claim_token").contains(">= 0");
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
        try (Connection connection = connection()) {
            return uniqueIndexColumns(connection);
        }
    }

    private List<String> uniqueIndexColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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
             ResultSet rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private Flyway flywayForTarget(String target) {
        var configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void cleanDatabase() {
        flywayForTarget(null).clean();
    }

    private long membershipCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM waiting_active_memberships");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
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

    private List<String> primaryKeyColumns(String tableName) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT column_name
                     FROM information_schema.key_column_usage
                     WHERE constraint_schema = DATABASE()
                       AND table_name = ?
                       AND constraint_name = 'PRIMARY'
                     ORDER BY ordinal_position
                     """)) {
            statement.setString(1, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
                return columns;
            }
        }
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

    private Set<String> quotedValues(String checkClause) {
        Matcher matcher = Pattern.compile("'([^']+)'").matcher(checkClause);
        Set<String> values = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1).replace("\\", ""));
        }
        return values;
    }

    private void assertTeamSnapshotRejected(
            Connection connection,
            long queueSequence,
            String status,
            Instant createdAt,
            Instant calledAt,
            Instant deadline,
            Instant arrivedAt
    ) {
        assertThatThrownBy(() -> insertTeam(
                connection,
                queueSequence,
                status,
                createdAt,
                calledAt,
                deadline,
                arrivedAt,
                null,
                null,
                null,
                null
        ))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_waiting_teams_call_window");
    }

    private void insertTeam(
            Connection connection,
            long queueSequence,
            String status,
            Instant createdAt,
            Instant calledAt,
            Instant deadline,
            Instant arrivedAt,
            Instant checkedInAt,
            Instant cancelledAt,
            Instant noShowAt,
            Instant closedByStoreAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_teams (
                    store_id, consumer_account_id, business_date, party_size, source,
                    queue_sequence, status, version, created_at, called_at,
                    arrival_deadline, arrived_at, checked_in_at, cancelled_at,
                    no_show_at, closed_by_store_at, reservation_converting_at,
                    waiting_payment_id
                ) VALUES (99001, 99002, ?, 2, 'REMOTE', ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, LocalDate.of(2026, 8, 12));
            statement.setLong(2, queueSequence);
            statement.setString(3, status);
            setInstant(statement, 4, createdAt);
            setInstant(statement, 5, calledAt);
            setInstant(statement, 6, deadline);
            setInstant(statement, 7, arrivedAt);
            setInstant(statement, 8, checkedInAt);
            setInstant(statement, 9, cancelledAt);
            setInstant(statement, 10, noShowAt);
            setInstant(statement, 11, closedByStoreAt);
            boolean conversionInProgress = "RESERVATION_CONVERTING".equals(status);
            setInstant(statement, 12, conversionInProgress ? createdAt : null);
            statement.setString(13, conversionInProgress ? "123456789" : null);
            statement.executeUpdate();
        }
    }

    private void assertConversionSnapshotRejected(
            Connection connection,
            long queueSequence,
            String status,
            Instant createdAt,
            Instant reservationConvertingAt,
            String waitingPaymentId,
            Long reservationReferenceId,
            Instant reservationConvertedAt,
            Instant cancelledAt,
            Instant closedByStoreAt
    ) {
        assertThatThrownBy(() -> insertConversionTeam(
                connection,
                queueSequence,
                status,
                createdAt,
                reservationConvertingAt,
                waitingPaymentId,
                reservationReferenceId,
                reservationConvertedAt,
                cancelledAt,
                closedByStoreAt
        )).isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_waiting_teams_reservation_conversion");
    }

    private void insertConversionTeam(
            Connection connection,
            long queueSequence,
            String status,
            Instant createdAt,
            Instant reservationConvertingAt,
            String waitingPaymentId,
            Long reservationReferenceId,
            Instant reservationConvertedAt,
            Instant cancelledAt,
            Instant closedByStoreAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_teams (
                    store_id, consumer_account_id, business_date, party_size, source,
                    queue_sequence, status, version, created_at, cancelled_at,
                    closed_by_store_at, reservation_converting_at, waiting_payment_id,
                    reservation_reference_id, reservation_converted_at
                ) VALUES (99001, 99002, ?, 2, 'REMOTE', ?, ?, 0, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, LocalDate.of(2026, 8, 12));
            statement.setLong(2, queueSequence);
            statement.setString(3, status);
            setInstant(statement, 4, createdAt);
            setInstant(statement, 5, cancelledAt);
            setInstant(statement, 6, closedByStoreAt);
            setInstant(statement, 7, reservationConvertingAt);
            statement.setString(8, waitingPaymentId);
            if (reservationReferenceId == null) {
                statement.setObject(9, null);
            } else {
                statement.setLong(9, reservationReferenceId);
            }
            setInstant(statement, 10, reservationConvertedAt);
            statement.executeUpdate();
        }
    }

    private void insertLegacyReservationConvertingTeam(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_teams (
                    store_id, consumer_account_id, business_date, party_size, source,
                    queue_sequence, status, version, created_at
                ) VALUES (99001, 99002, ?, 2, 'REMOTE', 901, 'RESERVATION_CONVERTING', 0, ?)
                """)) {
            statement.setObject(1, LocalDate.of(2026, 8, 12));
            setInstant(statement, 2, Instant.parse("2026-08-12T03:00:00Z"));
            statement.executeUpdate();
        }
    }

    private void setInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        statement.setTimestamp(index, value == null ? null : Timestamp.from(value));
    }

    private void insertAudit(
            Connection connection,
            String commandId,
            String beforeStatus,
            String afterStatus,
            long expectedVersion,
            long resultVersion,
            Instant occurredAt,
            Instant createdAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO waiting_transition_audits (
                    waiting_team_id, actor_type, actor_id, before_status, after_status,
                    expected_version, result_version, reason, command_id, occurred_at, created_at
                ) VALUES (999999, 'SYSTEM', NULL, ?, ?, ?, ?, 'TEST', ?, ?, ?)
                """)) {
            statement.setString(1, beforeStatus);
            statement.setString(2, afterStatus);
            statement.setLong(3, expectedVersion);
            statement.setLong(4, resultVersion);
            statement.setString(5, commandId);
            setInstant(statement, 6, occurredAt);
            setInstant(statement, 7, createdAt);
            statement.executeUpdate();
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
