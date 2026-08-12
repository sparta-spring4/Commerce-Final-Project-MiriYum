package com.miriyum.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import com.miriyum.domain.reservation.entity.ReservationHoldWarningTask;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
class ReservationHoldMigrationTest {

    private static final long CONSUMER_ACCOUNT_ID = 41_001L;
    private static final long SECOND_CONSUMER_ACCOUNT_ID = 41_002L;
    private static final long STORE_OPERATOR_ACCOUNT_ID = 42_001L;
    private static final long STORE_ID = 43_001L;
    private static final Instant CREATED_AT = Instant.parse("2026-08-12T01:00:00Z");
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

    @Autowired
    private ReservationHoldRepository holdRepository;

    @Autowired
    private ReservationHoldCapacityAllocationRepository allocationRepository;

    @Autowired
    private ReservationHoldTransitionAuditRepository auditRepository;

    @Autowired
    private ReservationHoldWarningTaskRepository warningTaskRepository;

    @Autowired
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetAndSeedParents() {
        jdbcTemplate.execute("DELETE FROM reservation_hold_warning_tasks");
        jdbcTemplate.execute("DELETE FROM reservation_hold_transition_audits");
        jdbcTemplate.execute("DELETE FROM reservation_hold_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservation_holds");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");

        jdbcTemplate.update(
                """
                        INSERT INTO consumer_accounts (
                            consumer_account_id, email, password_hash, name, status, created_at, updated_at
                        ) VALUES (?, 'hold-consumer@example.com', 'hashed', '선점 사용자',
                                  'ACTIVE', NOW(6), NOW(6))
                        """,
                CONSUMER_ACCOUNT_ID
        );
        jdbcTemplate.update(
                """
                        INSERT INTO consumer_accounts (
                            consumer_account_id, email, password_hash, name, status, created_at, updated_at
                        ) VALUES (?, 'second-hold-consumer@example.com', 'hashed', '두 번째 선점 사용자',
                                  'ACTIVE', NOW(6), NOW(6))
                        """,
                SECOND_CONSUMER_ACCOUNT_ID
        );
        jdbcTemplate.update(
                """
                        INSERT INTO store_operator_accounts (
                            store_operator_account_id, email, password_hash, display_name, status,
                            created_at, updated_at
                        ) VALUES (?, 'hold-owner@example.com', 'hashed', '선점 운영자',
                                  'ACTIVE', NOW(6), NOW(6))
                        """,
                STORE_OPERATOR_ACCOUNT_ID
        );
        jdbcTemplate.update(
                """
                        INSERT INTO stores (
                            store_id, store_operator_account_id, business_registration_number,
                            business_type, name, description, region, address, time_zone_id,
                            applicant_self_attested_at, required_terms_agreed_at,
                            required_terms_version, store_category_code, verification_status,
                            operation_status, reservation_enabled, menu_hold_enabled,
                            pickup_enabled, created_at, updated_at
                        ) VALUES (
                            ?, ?, '9876543210', 'CAFE', '선점 매장', '', 'SEOUL', '서울시 중구',
                            'Asia/Seoul', NOW(6), NOW(6),
                            'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY',
                            'APPROVED', 'OPEN', TRUE, TRUE, TRUE, NOW(6), NOW(6)
                        )
                        """,
                STORE_ID,
                STORE_OPERATOR_ACCOUNT_ID
        );
    }

    @Test
    void appliesReservationHoldPersistenceAsFlywayV31() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();

        flyway.migrate();

        assertThat(flyway.info().applied())
                .anyMatch(migration ->
                        "31".equals(String.valueOf(migration.getVersion()))
                                && "V31__create_reservation_holds.sql"
                                .equals(migration.getScript()));
    }

    @Test
    void createsSeparateHoldAllocationAuditAndWarningTables() throws SQLException {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();

        assertThat(reservationHoldTables()).containsExactlyInAnyOrder(
                "reservation_holds",
                "reservation_hold_capacity_allocations",
                "reservation_hold_transition_audits",
                "reservation_hold_warning_tasks"
        );
    }

    @Test
    void createsCompleteReservationHoldSnapshotColumns() throws SQLException {
        migrate();

        assertThat(columnsOf("reservation_holds")).containsExactly(
                "reservation_hold_id",
                "consumer_account_id",
                "store_id",
                "store_name_snapshot",
                "service_date",
                "start_at",
                "service_end_at",
                "occupancy_end_at",
                "time_zone_id_snapshot",
                "start_offset_seconds",
                "service_end_offset_seconds",
                "occupancy_end_offset_seconds",
                "slot_interval_minutes",
                "service_duration_minutes",
                "turnover_duration_minutes",
                "reservation_time_policy_store_id",
                "reservation_policy_version",
                "adult_count",
                "child_count",
                "infant_count",
                "notification_target_reference",
                "contact_available_at_confirmation",
                "capacity_policy_version",
                "cancellation_policy_version",
                "status",
                "status_version",
                "creation_command_id",
                "created_at",
                "expires_at"
        );
    }

    @Test
    void createsCompleteAllocationAuditAndWarningColumns() throws SQLException {
        migrate();

        assertThat(columnsOf("reservation_hold_capacity_allocations")).containsExactly(
                "reservation_hold_capacity_allocation_id",
                "reservation_hold_id",
                "reservation_capacity_bucket_id",
                "occupied_people",
                "occupied_teams",
                "capacity_policy_version"
        );
        assertThat(columnsOf("reservation_hold_transition_audits")).containsExactly(
                "reservation_hold_transition_audit_id",
                "reservation_hold_id",
                "actor_type",
                "actor_id",
                "requested_at",
                "occurred_at",
                "before_status",
                "after_status",
                "reservation_time_policy_version",
                "capacity_policy_version",
                "command_id"
        );
        assertThat(columnsOf("reservation_hold_warning_tasks")).containsExactly(
                "reservation_hold_warning_task_id",
                "reservation_hold_id",
                "created_at",
                "warning_due_at"
        );
    }

    @Test
    void declaresHoldForeignKeyUniqueAndCheckConstraints() throws SQLException {
        migrate();

        assertThat(reservationHoldConstraints()).contains(
                "reservation_holds.uk_reservation_holds_creation_command",
                "reservation_holds.uk_reservation_holds_id_created",
                "reservation_holds.fk_reservation_holds_consumer_account",
                "reservation_holds.fk_reservation_holds_store",
                "reservation_holds.ck_reservation_holds_store_name",
                "reservation_holds.ck_reservation_holds_time_snapshot",
                "reservation_holds.ck_reservation_holds_party_counts",
                "reservation_holds.ck_reservation_holds_contact",
                "reservation_holds.ck_reservation_holds_policy_versions",
                "reservation_holds.ck_reservation_holds_status",
                "reservation_holds.ck_reservation_holds_status_version",
                "reservation_holds.ck_reservation_holds_creation_command",
                "reservation_holds.ck_reservation_holds_expiration",
                "reservation_hold_capacity_allocations.uk_reservation_hold_capacity_allocations_hold_bucket",
                "reservation_hold_capacity_allocations.fk_reservation_hold_capacity_allocations_hold",
                "reservation_hold_capacity_allocations.fk_reservation_hold_capacity_allocations_bucket",
                "reservation_hold_capacity_allocations.ck_reservation_hold_capacity_allocations_people",
                "reservation_hold_capacity_allocations.ck_reservation_hold_capacity_allocations_teams",
                "reservation_hold_capacity_allocations.ck_reservation_hold_capacity_allocations_policy",
                "reservation_hold_transition_audits.uk_reservation_hold_transition_audits_command",
                "reservation_hold_transition_audits.fk_reservation_hold_transition_audits_hold",
                "reservation_hold_transition_audits.ck_reservation_hold_transition_audits_actor",
                "reservation_hold_transition_audits.ck_reservation_hold_transition_audits_time",
                "reservation_hold_transition_audits.ck_reservation_hold_transition_audits_status",
                "reservation_hold_transition_audits.ck_reservation_hold_transition_audits_policy",
                "reservation_hold_transition_audits.ck_reservation_hold_transition_audits_command",
                "reservation_hold_warning_tasks.uk_reservation_hold_warning_tasks_hold",
                "reservation_hold_warning_tasks.fk_reservation_hold_warning_tasks_hold",
                "reservation_hold_warning_tasks.ck_reservation_hold_warning_tasks_due"
        );
    }

    @Test
    void rejectsMissingActorIdForNonSystemTransitionAudit() {
        ReservationHold hold = holdRepository.saveAndFlush(hold());

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO reservation_hold_transition_audits (
                            reservation_hold_id, actor_type, actor_id,
                            requested_at, occurred_at, before_status, after_status,
                            reservation_time_policy_version, capacity_policy_version, command_id
                        ) VALUES (?, 'CONSUMER', NULL, ?, ?, NULL, 'ACTIVE', 5, 3, ?)
                        """,
                hold.getId(),
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT),
                "reservation-hold:audit:missing-consumer-id"
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_hold_transition_audits_actor");
    }

    @Test
    void scopesCreationCommandUniquenessToConsumerAccount() {
        String sharedCommandId = "reservation-hold:create:shared-client-key";

        ReservationHold first = holdRepository.saveAndFlush(
                hold(CONSUMER_ACCOUNT_ID, sharedCommandId)
        );
        ReservationHold second = holdRepository.saveAndFlush(
                hold(SECOND_CONSUMER_ACCOUNT_ID, sharedCommandId)
        );

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ACCOUNT_ID,
                sharedCommandId
        )).get().extracting(ReservationHold::getId).isEqualTo(first.getId());
        assertThat(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                SECOND_CONSUMER_ACCOUNT_ID,
                sharedCommandId
        )).get().extracting(ReservationHold::getId).isEqualTo(second.getId());
    }

    @Test
    void rejectsInvalidRootAllocationAndWarningPersistenceValues() {
        ReservationCapacityBucket bucket = capacityBucketRepository.saveAndFlush(
                ReservationCapacityBucket.create(
                        STORE_ID,
                        LocalDate.of(2026, 8, 12),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        1,
                        8,
                        true,
                        3L
                )
        );
        ReservationHold first = holdRepository.saveAndFlush(hold("reservation-hold:create:first"));
        ReservationHold second = holdRepository.saveAndFlush(hold("reservation-hold:create:second"));

        assertConstraintViolation(
                "UPDATE reservation_holds SET status = 'INVALID' WHERE reservation_hold_id = ?",
                "ck_reservation_holds_status",
                first.getId()
        );
        assertConstraintViolation(
                "UPDATE reservation_holds SET expires_at = TIMESTAMPADD(MINUTE, 9, created_at) "
                        + "WHERE reservation_hold_id = ?",
                "ck_reservation_holds_expiration",
                first.getId()
        );
        assertConstraintViolation(
                "UPDATE reservation_holds SET creation_command_id = ? "
                        + "WHERE reservation_hold_id = ?",
                "uk_reservation_holds_creation_command",
                first.getCreationCommandId(),
                second.getId()
        );
        assertConstraintViolation(
                """
                        INSERT INTO reservation_hold_capacity_allocations (
                            reservation_hold_id, reservation_capacity_bucket_id,
                            occupied_people, occupied_teams, capacity_policy_version
                        ) VALUES (?, ?, 0, 1, 3)
                        """,
                "ck_reservation_hold_capacity_allocations_people",
                first.getId(),
                bucket.getId()
        );

        jdbcTemplate.update(
                """
                        INSERT INTO reservation_hold_warning_tasks (
                            reservation_hold_id, created_at, warning_due_at
                        )
                        SELECT reservation_hold_id, created_at,
                               TIMESTAMPADD(MINUTE, 8, created_at)
                        FROM reservation_holds
                        WHERE reservation_hold_id = ?
                        """,
                first.getId()
        );
        assertConstraintViolation(
                """
                        INSERT INTO reservation_hold_warning_tasks (
                            reservation_hold_id, created_at, warning_due_at
                        )
                        SELECT reservation_hold_id, created_at,
                               TIMESTAMPADD(MINUTE, 8, created_at)
                        FROM reservation_holds
                        WHERE reservation_hold_id = ?
                        """,
                "uk_reservation_hold_warning_tasks_hold",
                first.getId()
        );
        assertConstraintViolation(
                """
                        INSERT INTO reservation_hold_warning_tasks (
                            reservation_hold_id, created_at, warning_due_at
                        )
                        SELECT reservation_hold_id,
                               TIMESTAMPADD(SECOND, 1, created_at),
                               TIMESTAMPADD(MINUTE, 8, TIMESTAMPADD(SECOND, 1, created_at))
                        FROM reservation_holds
                        WHERE reservation_hold_id = ?
                        """,
                "fk_reservation_hold_warning_tasks_hold",
                second.getId()
        );
    }

    @Test
    void indexesOwnerExpirationAndChildLookupPaths() throws SQLException {
        migrate();

        assertThat(reservationHoldIndexes()).contains(
                "reservation_holds.idx_reservation_holds_consumer_service",
                "reservation_holds.idx_reservation_holds_expiration",
                "reservation_hold_capacity_allocations.idx_reservation_hold_capacity_allocations_bucket",
                "reservation_hold_transition_audits.idx_reservation_hold_transition_audits_hold",
                "reservation_hold_warning_tasks.idx_reservation_hold_warning_tasks_due"
        );
    }

    @Test
    void jpaRoundTripsHoldAllocationAuditAndWarningObligation() {
        ReservationCapacityBucket bucket = capacityBucketRepository.saveAndFlush(
                ReservationCapacityBucket.create(
                        STORE_ID,
                        LocalDate.of(2026, 8, 12),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        1,
                        8,
                        true,
                        3L
                )
        );
        ReservationHold hold = holdRepository.saveAndFlush(hold());
        ReservationHoldCapacityAllocation allocation = allocationRepository.saveAndFlush(
                ReservationHoldCapacityAllocation.allocate(
                        hold.getId(), bucket.getId(), 3, 3L)
        );
        ReservationHoldTransitionAudit audit = auditRepository.save(
                ReservationHoldTransitionAudit.record(
                        hold.getId(), "CONSUMER", CONSUMER_ACCOUNT_ID,
                        CREATED_AT, CREATED_AT,
                        null, ReservationHoldStatus.ACTIVE,
                        5L, 3L,
                        "reservation-hold:create:550e8400-e29b-41d4-a716-446655440000"
                )
        );
        ReservationHoldWarningTask warning = warningTaskRepository.saveAndFlush(
                ReservationHoldWarningTask.schedule(
                        hold.getId(), hold.getCreatedAt(), hold.getExpiresAt())
        );

        entityManager.clear();

        assertThat(holdRepository.findById(hold.getId()))
                .get()
                .satisfies(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
                    assertThat(saved.getExpiresAt())
                            .isEqualTo(CREATED_AT.plus(Duration.ofMinutes(10)));
                    assertThat(saved.getCreationCommandId())
                            .isEqualTo("reservation-hold:create:root");
                });
        assertThat(allocationRepository
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(hold.getId()))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getId()).isEqualTo(allocation.getId());
                    assertThat(saved.getOccupiedPeople()).isEqualTo(3);
                    assertThat(saved.getOccupiedTeams()).isOne();
                });
        assertThat(auditRepository.findAllByReservationHoldIdOrderByIdAsc(hold.getId()))
                .singleElement()
                .satisfies(saved -> assertThat(saved.getId()).isEqualTo(audit.getId()));
        assertThat(warningTaskRepository.findByReservationHoldId(hold.getId()))
                .get()
                .satisfies(saved -> {
                    assertThat(saved.getId()).isEqualTo(warning.getId());
                    assertThat(saved.getWarningDueAt())
                            .isEqualTo(CREATED_AT.plus(Duration.ofMinutes(8)));
                });
    }

    private ReservationHold hold() {
        return hold("reservation-hold:create:root");
    }

    private ReservationHold hold(String commandId) {
        return hold(CONSUMER_ACCOUNT_ID, commandId);
    }

    private ReservationHold hold(long consumerAccountId, String commandId) {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                STORE_ID, 5L, 30, 90, 15);
        policy.activate(CREATED_AT.minusSeconds(3600), "활성 정책");
        ReservationTimeSnapshot timeSnapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 12, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );
        return ReservationHold.active(
                consumerAccountId,
                STORE_ID,
                "선점 매장",
                timeSnapshot,
                PartyComposition.of(2, 1, 0),
                ReservationContactSnapshot.contactable(
                        "consumer:" + consumerAccountId + ":channel:primary"),
                3L,
                new ReservationCancellationPolicyVersion(1L),
                commandId,
                CREATED_AT
        );
    }

    private void assertConstraintViolation(
            String sql,
            String constraintName,
            Object... arguments
    ) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, arguments))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(constraintName);
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    private List<String> columnsOf(String tableName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT column_name
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = ?
                     ORDER BY ordinal_position
                     """)) {
            statement.setString(1, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> columnNames = new ArrayList<>();
                while (rows.next()) {
                    columnNames.add(rows.getString("column_name"));
                }
                return columnNames;
            }
        }
    }

    private List<String> reservationHoldConstraints() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT CONCAT(table_name, '.', constraint_name) AS qualified_name
                     FROM information_schema.table_constraints
                     WHERE constraint_schema = DATABASE()
                       AND table_name LIKE 'reservation_hold%'
                       AND constraint_name <> 'PRIMARY'
                     ORDER BY table_name, constraint_name
                     """);
             ResultSet rows = statement.executeQuery()) {
            List<String> constraints = new ArrayList<>();
            while (rows.next()) {
                constraints.add(rows.getString("qualified_name"));
            }
            return constraints;
        }
    }

    private List<String> reservationHoldIndexes() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT CONCAT(table_name, '.', index_name) AS qualified_name
                     FROM information_schema.statistics
                     WHERE table_schema = DATABASE()
                       AND table_name LIKE 'reservation_hold%'
                       AND index_name <> 'PRIMARY'
                     ORDER BY qualified_name
                     """);
             ResultSet rows = statement.executeQuery()) {
            List<String> indexes = new ArrayList<>();
            while (rows.next()) {
                indexes.add(rows.getString("qualified_name"));
            }
            return indexes;
        }
    }

    private List<String> reservationHoldTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name LIKE 'reservation_hold%'
                     ORDER BY table_name
                     """);
             ResultSet rows = statement.executeQuery()) {
            List<String> tableNames = new ArrayList<>();
            while (rows.next()) {
                tableNames.add(rows.getString("table_name"));
            }
            return tableNames;
        }
    }
}
