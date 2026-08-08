package com.miriyum.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.response.CustomerReservationTimeResponse;
import com.miriyum.domain.reservation.dto.response.CustomerReservationTimeStatus;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationActorType;
import com.miriyum.domain.reservation.entity.ReservationCancellationAudit;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 예약 코어 Flyway 스키마와 JPA 매핑이 실제 MySQL 제약에서 같은 계약을 지키는지 검증한다.
 */
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
class ReservationMigrationTest {

    private static final long CONSUMER_ACCOUNT_ID = 10_001L;
    private static final long STORE_OPERATOR_ACCOUNT_ID = 20_001L;
    private static final long STORE_ID = 30_001L;
    private static final long SECOND_STORE_ID = 30_002L;
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T01:00:00Z");
    private static final String NOTIFICATION_TARGET_REFERENCE =
            "consumer:10001:channel:primary";

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationCancellationAuditRepository cancellationAuditRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ReservationTimePolicyVersionRepository timePolicyRepository;

    @Autowired
    private ReservationTimePolicyAuditRepository timePolicyAuditRepository;

    @Autowired
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private ReservationCapacityAllocationRepository capacityAllocationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void resetRowsAndSeedParents() {
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservation_cancellation_audits");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_audits");
        jdbcTemplate.execute("DELETE FROM reservation_time_policy_versions");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");

        jdbcTemplate.update(
                """
                        INSERT INTO consumer_accounts (
                            consumer_account_id,
                            email,
                            password_hash,
                            name,
                            status,
                            created_at,
                            updated_at
                        )
                        VALUES (?, 'reservation-test@example.com', 'hashed', '예약자', 'ACTIVE', NOW(6), NOW(6))
                        """,
                CONSUMER_ACCOUNT_ID
        );
        jdbcTemplate.update(
                """
                        INSERT INTO store_operator_accounts (
                            store_operator_account_id,
                            email,
                            password_hash,
                            display_name,
                            status,
                            created_at,
                            updated_at
                        )
                        VALUES (?, 'reservation-owner@example.com', 'hashed', '운영자', 'ACTIVE', NOW(6), NOW(6))
                        """,
                STORE_OPERATOR_ACCOUNT_ID
        );
        jdbcTemplate.update(
                """
                        INSERT INTO stores (
                            store_id,
                            store_operator_account_id,
                            business_registration_number,
                            business_type,
                            name,
                            description,
                            region,
                            address,
                            time_zone_id,
                            applicant_self_attested_at,
                            required_terms_agreed_at,
                            required_terms_version,
                            store_category_code,
                            verification_status,
                            operation_status,
                            reservation_enabled,
                            menu_hold_enabled,
                            pickup_enabled,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            ?, ?, '1234567890', 'CAFE', '미리윰', '', 'SEOUL', '서울시 중구',
                            'Asia/Seoul', NOW(6), NOW(6), 'STORE_ONBOARDING_REQUIRED_TERMS_V1',
                            'CAFE_BAKERY', 'APPROVED', 'OPEN',
                            TRUE, TRUE, TRUE, NOW(6), NOW(6)
                        )
                        """,
                STORE_ID,
                STORE_OPERATOR_ACCOUNT_ID
        );
    }

    @Test
    @DisplayName("예약 코어 스키마는 Flyway V15로 적용된다")
    void appliesReservationCoreAsFlywayV15() {
        assertThat(flyway.info().applied())
                .anyMatch(migration ->
                        "15".equals(String.valueOf(migration.getVersion()))
                                && "V15__create_reservation_core.sql".equals(migration.getScript()));
    }

    @Test
    @DisplayName("예약 시간 정책과 Instant 스냅샷 확장은 Flyway V20으로 적용된다")
    void appliesReservationTimePolicyAsFlywayV20() {
        assertThat(flyway.info().applied())
                .anyMatch(migration ->
                        "20".equals(String.valueOf(migration.getVersion()))
                                && "V20__create_reservation_time_policies.sql"
                                .equals(migration.getScript()));
    }

    @Test
    @DisplayName("예약 취소 정책 스냅샷 확장은 Flyway V24로 적용된다")
    void appliesReservationCancellationPolicyAsFlywayV24() {
        assertThat(flyway.info().applied())
                .anyMatch(migration ->
                        "24".equals(String.valueOf(migration.getVersion()))
                                && "V24__add_reservation_cancellation_contract.sql"
                                .equals(migration.getScript()));
    }

    @Test
    @DisplayName("성공 취소 감사 스키마는 Flyway V26으로 적용된다")
    void appliesCancellationAuditAsFlywayV26() {
        assertThat(flyway.info().applied())
                .anyMatch(migration ->
                        "26".equals(String.valueOf(migration.getVersion()))
                                && "V26__create_reservation_cancellation_audits.sql"
                                .equals(migration.getScript()));
    }

    @Test
    @DisplayName("Flyway 감사 테이블과 JPA 매핑으로 성공 취소 감사를 저장하고 예약으로 조회한다")
    void persistsCancellationAuditAndFindsItByReservationId() {
        Reservation savedReservation = reservationRepository.saveAndFlush(reservation());
        ReservationCancellationAudit savedAudit = cancellationAuditRepository.saveAndFlush(
                cancellationAudit(savedReservation.getId(), "운영자 취소")
        );

        entityManager.clear();

        ReservationCancellationAudit found = cancellationAuditRepository
                .findByReservationId(savedReservation.getId())
                .orElseThrow();

        assertThat(found.getId()).isEqualTo(savedAudit.getId());
        assertThat(found.getReservationId()).isEqualTo(savedReservation.getId());
        assertThat(found.getActorType()).isEqualTo(ReservationCancellationActorType.STORE_OPERATOR);
        assertThat(found.getCancellationReason()).isEqualTo("운영자 취소");
        assertThat(found.getCommandId()).isEqualTo(cancellationAuditCommandId());
    }

    @Test
    @DisplayName("성공 취소 감사의 MySQL 제약은 중복·참조·actor·사유·시각·상태·버전·명령을 거부한다")
    void rejectsInvalidCancellationAuditRows() {
        Reservation savedReservation = reservationRepository.saveAndFlush(reservation());
        insertCancellationAudit(
                savedReservation.getId(),
                "STORE_OPERATOR",
                STORE_OPERATOR_ACCOUNT_ID,
                "운영자 취소",
                CREATED_AT,
                CREATED_AT.plusSeconds(1),
                "CONFIRMED",
                "CANCELLED",
                1L,
                1L,
                cancellationAuditCommandId()
        );

        assertThatThrownBy(() -> insertCancellationAudit(
                savedReservation.getId(), "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                "운영자 취소", CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                1L, 1L, "reservation-cancel:store-operator:20001:duplicate"
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_reservation_cancellation_audits_reservation");
        assertThatThrownBy(() -> insertCancellationAudit(
                999_999L, "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                "운영자 취소", CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                1L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_reservation_cancellation_audits_reservation");
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "SYSTEM", STORE_OPERATOR_ACCOUNT_ID,
                "운영자 취소", CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                1L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_cancellation_audits_actor_type");
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "CONSUMER", 0L,
                null, CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                1L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_cancellation_audits_actor_id");
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                null, CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                1L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_cancellation_audits_reason");
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                "", CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                1L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_cancellation_audits_reason");
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                "a".repeat(501), CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                1L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                "운영자 취소", CREATED_AT.plusSeconds(1), CREATED_AT, "CONFIRMED", "CANCELLED",
                1L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_cancellation_audits_time");
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                "운영자 취소", CREATED_AT, CREATED_AT.plusSeconds(1), "CANCELLED", "CANCELLED",
                1L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_cancellation_audits_transition");
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                "운영자 취소", CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                0L, 1L, cancellationAuditCommandId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_cancellation_audits_versions");
        assertThatThrownBy(() -> insertCancellationAudit(
                reservationRepository.saveAndFlush(reservation()).getId(), "STORE_OPERATOR", STORE_OPERATOR_ACCOUNT_ID,
                "운영자 취소", CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED",
                1L, 1L, "a".repeat(101)
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("소비자 null 사유·운영자 공백 사유·90자 correlation은 JDBC와 JPA에서 보존된다")
    void acceptsCancellationAuditCompatibilityBoundaries() {
        Reservation consumerReservation = reservationRepository.saveAndFlush(reservation());
        Reservation operatorReservation = reservationRepository.saveAndFlush(reservation());
        String maxCorrelation = "reservation-cancel:store-operator:" + Long.MAX_VALUE + ":" + "a".repeat(36);

        insertCancellationAudit(
                consumerReservation.getId(), "CONSUMER", CONSUMER_ACCOUNT_ID, null,
                CREATED_AT, CREATED_AT.plusSeconds(1), "CONFIRMED", "CANCELLED", 1L, 1L,
                "reservation-cancel:consumer:10001:jdbc"
        );
        ReservationCancellationAudit saved = cancellationAuditRepository.saveAndFlush(
                ReservationCancellationAudit.recordSuccess(
                        operatorReservation.getId(),
                        ReservationCancellationActorType.STORE_OPERATOR,
                        STORE_OPERATOR_ACCOUNT_ID,
                        " ",
                        CREATED_AT,
                        CREATED_AT.plusSeconds(1),
                        ReservationStatus.CONFIRMED,
                        ReservationStatus.CANCELLED,
                        1L,
                        1L,
                        maxCorrelation
                )
        );

        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM reservation_cancellation_audits WHERE reservation_id = ?",
                String.class,
                consumerReservation.getId()
        )).isNull();
        assertThat(cancellationAuditRepository.findByReservationId(operatorReservation.getId()))
                .get()
                .extracting(
                        ReservationCancellationAudit::getId,
                        ReservationCancellationAudit::getCancellationReason,
                        ReservationCancellationAudit::getCommandId
                )
                .containsExactly(saved.getId(), " ", maxCorrelation);
    }

    @Test
    @DisplayName("예약 취소 actor 범위 조회는 상태를 숨기지 않고 실제 쓰기 잠금을 유지한다")
    void reservationRepositoryContractsScopeAndHoldWriteLocks() throws Exception {
        // given
        long secondConsumerAccountId = CONSUMER_ACCOUNT_ID + 1;
        jdbcTemplate.update(
                """
                        INSERT INTO consumer_accounts (
                            consumer_account_id, email, password_hash, name, status,
                            created_at, updated_at
                        ) VALUES (?, 'second-reservation-test@example.com', 'hashed',
                                  '두 번째 예약자', 'ACTIVE', NOW(6), NOW(6))
                        """,
                secondConsumerAccountId
        );
        insertStore(SECOND_STORE_ID, "1234567891", "두 번째 매장");

        Reservation consumerReservation = reservationRepository.saveAndFlush(
                reservation(CONSUMER_ACCOUNT_ID, STORE_ID)
        );
        Reservation operatorReservation = reservationRepository.saveAndFlush(
                reservation(secondConsumerAccountId, SECOND_STORE_ID)
        );
        Reservation cancelledReservation = reservation(
                secondConsumerAccountId, SECOND_STORE_ID);
        cancelledReservation.cancel(CREATED_AT.plusSeconds(1));
        Reservation savedCancelledReservation =
                reservationRepository.saveAndFlush(cancelledReservation);

        Method consumerLockMethod = requiredRepositoryMethod(
                reservationRepository,
                "findByIdAndConsumerAccountIdForUpdate",
                2
        );
        Method operatorLockMethod = requiredRepositoryMethod(
                reservationRepository,
                "findByIdAndStoreIdForUpdate",
                2
        );

        // when
        @SuppressWarnings("unchecked")
        Optional<Reservation> consumerScoped = transactionTemplate.execute(status ->
                (Optional<Reservation>) invokeRepositoryMethod(
                        consumerLockMethod,
                        reservationRepository,
                        consumerReservation.getId(),
                        CONSUMER_ACCOUNT_ID
                )
        );
        @SuppressWarnings("unchecked")
        Optional<Reservation> operatorScoped = transactionTemplate.execute(status ->
                (Optional<Reservation>) invokeRepositoryMethod(
                        operatorLockMethod,
                        reservationRepository,
                        operatorReservation.getId(),
                        SECOND_STORE_ID
                )
        );
        @SuppressWarnings("unchecked")
        Optional<Reservation> foreignConsumer = transactionTemplate.execute(status ->
                (Optional<Reservation>) invokeRepositoryMethod(
                        consumerLockMethod,
                        reservationRepository,
                        consumerReservation.getId(),
                        secondConsumerAccountId
                )
        );
        @SuppressWarnings("unchecked")
        Optional<Reservation> foreignStore = transactionTemplate.execute(status ->
                (Optional<Reservation>) invokeRepositoryMethod(
                        operatorLockMethod,
                        reservationRepository,
                        operatorReservation.getId(),
                        STORE_ID
                )
        );
        @SuppressWarnings("unchecked")
        Optional<Reservation> cancelledForConsumer = transactionTemplate.execute(status ->
                (Optional<Reservation>) invokeRepositoryMethod(
                        consumerLockMethod,
                        reservationRepository,
                        savedCancelledReservation.getId(),
                        secondConsumerAccountId
                )
        );
        @SuppressWarnings("unchecked")
        Optional<Reservation> cancelledForStore = transactionTemplate.execute(status ->
                (Optional<Reservation>) invokeRepositoryMethod(
                        operatorLockMethod,
                        reservationRepository,
                        savedCancelledReservation.getId(),
                        SECOND_STORE_ID
                )
        );

        // then
        assertThat(consumerScoped)
                .get()
                .extracting(Reservation::getId, Reservation::getConsumerAccountId)
                .containsExactly(consumerReservation.getId(), CONSUMER_ACCOUNT_ID);
        assertThat(operatorScoped)
                .get()
                .extracting(Reservation::getId, Reservation::getStoreId)
                .containsExactly(operatorReservation.getId(), SECOND_STORE_ID);
        assertThat(foreignConsumer).isEmpty();
        assertThat(foreignStore).isEmpty();
        assertThat(cancelledForConsumer).get().extracting(Reservation::getStatus)
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelledForStore).get().extracting(Reservation::getStatus)
                .isEqualTo(ReservationStatus.CANCELLED);

        assertReservationWriteLock(
                consumerLockMethod,
                consumerReservation.getId(),
                CONSUMER_ACCOUNT_ID
        );
        assertReservationWriteLock(
                operatorLockMethod,
                operatorReservation.getId(),
                SECOND_STORE_ID
        );
    }

    @Test
    @DisplayName("취소 정책 버전 열은 nullable BIGINT이며 기본값 없이 양수만 허용한다")
    void definesNullablePositiveCancellationPolicyVersionWithoutDefault() {
        java.util.Map<String, Object> column = jdbcTemplate.queryForMap("""
                SELECT data_type, column_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'reservations'
                  AND column_name = 'cancellation_policy_version'
                """);

        assertThat(column)
                .containsEntry("data_type", "bigint")
                .containsEntry("column_type", "bigint")
                .containsEntry("is_nullable", "YES");
        assertThat(column.get("column_default")).isNull();

        java.util.Map<String, Object> constraint = jdbcTemplate.queryForMap("""
                SELECT tc.constraint_name, tc.constraint_type, cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_schema = tc.constraint_schema
                 AND cc.constraint_name = tc.constraint_name
                WHERE tc.table_schema = DATABASE()
                  AND tc.table_name = 'reservations'
                  AND tc.constraint_name = 'ck_reservations_cancellation_policy_version'
                """);

        assertThat(constraint)
                .containsEntry(
                        "constraint_name",
                        "ck_reservations_cancellation_policy_version"
                )
                .containsEntry("constraint_type", "CHECK");
        assertThat(String.valueOf(constraint.get("check_clause"))
                .replace("`", "")
                .replaceAll("[()\\s]", "")
                .toLowerCase(java.util.Locale.ROOT))
                .isEqualTo(
                        "cancellation_policy_versionisnull"
                                + "orcancellation_policy_version>0"
                );
    }

    @Test
    @DisplayName("활성 시간 정책은 효력 시각 경계부터 매장 목록으로 한 번에 조회된다")
    void findsEffectiveActiveTimePoliciesAtBoundary() {
        // given
        ReservationTimePolicyVersion policy = timePolicy(1L);
        policy.activate(CREATED_AT, "활성 정책");
        timePolicyRepository.saveAndFlush(policy);

        // when & then
        assertThat(timePolicyRepository.findResolutionCandidatesByStoreIds(
                java.util.Set.of(STORE_ID),
                ReservationTimePolicyStatus.ACTIVE,
                ReservationTimePolicyStatus.SCHEDULED,
                CREATED_AT.minusNanos(1_000)
        )).isEmpty();
        assertThat(timePolicyRepository.findResolutionCandidatesByStoreIds(
                java.util.Set.of(STORE_ID),
                ReservationTimePolicyStatus.ACTIVE,
                ReservationTimePolicyStatus.SCHEDULED,
                CREATED_AT
        )).singleElement().satisfies(found -> {
            assertThat(found.getStoreId()).isEqualTo(STORE_ID);
            assertThat(found.getVersionNumber()).isEqualTo(1L);
            assertThat(found.getServiceDurationMinutes()).isEqualTo(90);
            assertThat(found.getTurnoverDurationMinutes()).isEqualTo(15);
        });
    }

    @Test
    @DisplayName("효력 시각에 도달한 SCHEDULED는 기존 ACTIVE와 함께 조회되어 실패 폐쇄 근거가 된다")
    void returnsDueScheduledAlongsideCurrentActiveForFailClosedResolution() {
        ReservationTimePolicyVersion active = timePolicy(1L);
        active.activate(CREATED_AT.minusSeconds(60), "기존 정책");
        timePolicyRepository.saveAndFlush(active);
        ReservationTimePolicyVersion scheduled = timePolicy(2L);
        scheduled.schedule(CREATED_AT, CREATED_AT.minusSeconds(120), "새 정책");
        timePolicyRepository.saveAndFlush(scheduled);

        assertThat(timePolicyRepository.findResolutionCandidatesByStoreIds(
                java.util.Set.of(STORE_ID),
                ReservationTimePolicyStatus.ACTIVE,
                ReservationTimePolicyStatus.SCHEDULED,
                CREATED_AT
        )).extracting(ReservationTimePolicyVersion::getStatus)
                .containsExactly(
                        ReservationTimePolicyStatus.ACTIVE,
                        ReservationTimePolicyStatus.SCHEDULED
                );
        assertThat(timePolicyRepository.findDueScheduledIds(
                ReservationTimePolicyStatus.SCHEDULED,
                CREATED_AT,
                PageRequest.of(0, 100)
        )).containsExactly(scheduled.getId());
    }

    @Test
    @DisplayName("게시 사유와 NOT_EVALUATED 감사 근거를 append-only 원장에 보존한다")
    void persistsPublicationReasonAndAuditWithoutInventingConflictCount() {
        ReservationTimePolicyVersion policy = timePolicy(1L);
        policy.schedule(CREATED_AT.plusSeconds(3600), CREATED_AT, "저녁 운영 확대");
        ReservationTimePolicyVersion saved = timePolicyRepository.saveAndFlush(policy);
        ReservationTimePolicyAudit audit = ReservationTimePolicyAudit.operatorCommand(
                STORE_ID,
                STORE_OPERATOR_ACCOUNT_ID,
                saved.getVersionNumber(),
                null,
                null,
                ReservationTimePolicyStatus.DRAFT,
                ReservationTimePolicyStatus.SCHEDULED,
                CREATED_AT,
                saved.getEffectiveAt(),
                CREATED_AT,
                "저녁 운영 확대",
                "550e8400-e29b-41d4-a716-446655440000"
        );
        ReservationTimePolicyAudit savedAudit =
                timePolicyAuditRepository.saveAndFlush(audit);

        assertThat(timePolicyRepository.findById(saved.getId()).orElseThrow()
                .getChangeReason()).isEqualTo("저녁 운영 확대");
        assertThat(timePolicyAuditRepository.findById(savedAudit.getId()).orElseThrow())
                .satisfies(found -> {
                    assertThat(found.getActorType())
                            .isEqualTo(ReservationTimePolicyAudit.ActorType.STORE_OPERATOR);
                    assertThat(found.getActorId()).isEqualTo(STORE_OPERATOR_ACCOUNT_ID);
                    assertThat(found.getConflictCheckStatus())
                            .isEqualTo(
                                    ReservationTimePolicyAudit.ConflictCheckStatus.NOT_EVALUATED
                            );
                    assertThat(found.getConflictCount()).isNull();
                    assertThat(found.getChangeReason()).isEqualTo("저녁 운영 확대");
                });
    }

    @Test
    @DisplayName("게시 상태 정책은 DB에서도 비어 있지 않은 changeReason을 요구한다")
    void rejectsPublishedPolicyWithoutChangeReason() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO reservation_time_policy_versions (
                            store_id,
                            version_number,
                            slot_interval_minutes,
                            service_duration_minutes,
                            turnover_duration_minutes,
                            status,
                            effective_at,
                            activated_at,
                            publication_requested_at,
                            change_reason,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, NULL, ?, NULL, ?, ?)
                        """,
                STORE_ID,
                1L,
                30,
                90,
                15,
                java.sql.Timestamp.from(CREATED_AT.plusSeconds(3600)),
                java.sql.Timestamp.from(CREATED_AT),
                java.sql.Timestamp.from(CREATED_AT),
                java.sql.Timestamp.from(CREATED_AT)
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_time_policy_lifecycle");
    }

    @Test
    @DisplayName("한 매장에 활성 정책 또는 미래 게시 정책이 각각 둘 이상 존재할 수 없다")
    void preventsOverlappingActiveAndScheduledPolicies() {
        ReservationTimePolicyVersion firstActive = timePolicy(1L);
        firstActive.activate(CREATED_AT, "첫 활성 정책");
        timePolicyRepository.saveAndFlush(firstActive);

        ReservationTimePolicyVersion secondActive = timePolicy(2L);
        secondActive.activate(CREATED_AT.plusSeconds(1), "두 번째 활성 정책");
        assertThatThrownBy(() -> timePolicyRepository.saveAndFlush(secondActive))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_reservation_time_policy_active_store");

        timePolicyRepository.deleteAll();
        timePolicyRepository.flush();

        ReservationTimePolicyVersion firstScheduled = timePolicy(3L);
        firstScheduled.schedule(
                CREATED_AT.plusSeconds(3600),
                CREATED_AT,
                "첫 예약 게시"
        );
        timePolicyRepository.saveAndFlush(firstScheduled);

        ReservationTimePolicyVersion secondScheduled = timePolicy(4L);
        secondScheduled.schedule(
                CREATED_AT.plusSeconds(7200),
                CREATED_AT,
                "두 번째 예약 게시"
        );
        assertThatThrownBy(() -> timePolicyRepository.saveAndFlush(secondScheduled))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_reservation_time_policy_scheduled_store");
    }

    @Test
    @DisplayName("Flyway 스키마와 JPA 매핑으로 예약·수용량 버킷·배정 이력을 저장한다")
    void persistsReservationCapacityAndAllocationWithFlywaySchema() {
        // given
        Reservation reservation = reservation();
        ReservationCapacityBucket bucket = capacityBucket(1L);

        // when
        Reservation savedReservation = reservationRepository.saveAndFlush(reservation);
        ReservationCapacityBucket savedBucket = capacityBucketRepository.saveAndFlush(bucket);
        ReservationCapacityAllocation savedAllocation =
                capacityAllocationRepository.saveAndFlush(
                        ReservationCapacityAllocation.allocate(
                                savedReservation.getId(),
                                savedBucket.getId(),
                                4,
                                1L
                        )
                );

        // then
        Reservation foundReservation =
                reservationRepository.findById(savedReservation.getId()).orElseThrow();
        ReservationCapacityBucket foundBucket =
                capacityBucketRepository.findById(savedBucket.getId()).orElseThrow();
        ReservationCapacityAllocation foundAllocation =
                capacityAllocationRepository.findById(savedAllocation.getId()).orElseThrow();

        assertThat(foundReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(foundReservation.getParty().totalCount()).isEqualTo(4);
        assertThat(foundReservation.getContactSnapshot().getNotificationTargetReference())
                .isEqualTo(NOTIFICATION_TARGET_REFERENCE);
        assertThat(foundReservation.getContactSnapshot().isContactAvailableAtConfirmation())
                .isTrue();
        assertThat(foundReservation.getStartAt())
                .isEqualTo(Instant.parse("2026-08-01T09:00:00Z"));
        assertThat(foundReservation.getServiceEndAt())
                .isEqualTo(Instant.parse("2026-08-01T10:30:00Z"));
        assertThat(foundReservation.getOccupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-01T10:45:00Z"));
        assertThat(foundReservation.getTimeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(foundReservation.getTimeSnapshot().getReservationTimePolicyStoreId())
                .isEqualTo(STORE_ID);
        assertThat(foundReservation.getReservationTimePolicyVersion()).isEqualTo(1L);
        assertThat(foundReservation.getCancellationPolicyVersion()).isEqualTo(1L);
        assertThat(foundBucket.getPolicyVersion()).isEqualTo(1L);
        assertThat(foundAllocation.getReservationId()).isEqualTo(savedReservation.getId());
        assertThat(foundAllocation.getCapacityBucketId()).isEqualTo(savedBucket.getId());
        assertThat(foundAllocation.getOccupiedTeams()).isOne();
    }

    @Test
    @DisplayName("계정 연락처가 바뀌어도 예약 당시 알림 대상과 연락 가능 상태를 보존한다")
    void preservesContactSnapshotAfterAccountContactChanges() {
        // given
        Reservation savedReservation = reservationRepository.saveAndFlush(reservation());

        // when
        jdbcTemplate.update(
                "UPDATE consumer_accounts SET phone = ? WHERE consumer_account_id = ?",
                "010-9999-9999",
                CONSUMER_ACCOUNT_ID
        );

        // then
        String targetReference = jdbcTemplate.queryForObject(
                """
                        SELECT notification_target_reference
                        FROM reservations
                        WHERE reservation_id = ?
                        """,
                String.class,
                savedReservation.getId()
        );
        Boolean contactAvailable = jdbcTemplate.queryForObject(
                """
                        SELECT contact_available_at_confirmation
                        FROM reservations
                        WHERE reservation_id = ?
                        """,
                Boolean.class,
                savedReservation.getId()
        );

        assertThat(targetReference).isEqualTo(NOTIFICATION_TARGET_REFERENCE);
        assertThat(contactAvailable).isTrue();
    }

    @Test
    @DisplayName("예약은 존재하는 일반 사용자 계정과 매장만 참조한다")
    void reservationRequiresExistingConsumerAndStore() {
        // when & then
        assertThatThrownBy(() -> insertReservation(99_999L, STORE_ID, "CONFIRMED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_reservations_consumer_account");
        assertThatThrownBy(() ->
                insertReservation(CONSUMER_ACCOUNT_ID, 99_999L, "CONFIRMED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_reservations_store");
    }

    @Test
    @DisplayName("같은 매장·날짜·구간·정책 버전의 수용량 버킷은 한 건만 저장된다")
    void capacityBucketBusinessKeyIsUnique() {
        // given
        capacityBucketRepository.saveAndFlush(capacityBucket(1L));

        // when & then
        assertThatThrownBy(() -> capacityBucketRepository.saveAndFlush(capacityBucket(1L)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_reservation_capacity_buckets_business_key");
    }

    @Test
    @DisplayName("같은 예약과 수용량 버킷의 배정 이력은 한 건만 저장된다")
    void reservationAndBucketAllocationIsUnique() {
        // given
        Reservation savedReservation = reservationRepository.saveAndFlush(reservation());
        ReservationCapacityBucket savedBucket =
                capacityBucketRepository.saveAndFlush(capacityBucket(1L));
        capacityAllocationRepository.saveAndFlush(
                ReservationCapacityAllocation.allocate(
                        savedReservation.getId(),
                        savedBucket.getId(),
                        4,
                        1L
                )
        );

        // when & then
        assertThatThrownBy(() -> capacityAllocationRepository.saveAndFlush(
                ReservationCapacityAllocation.allocate(
                        savedReservation.getId(),
                        savedBucket.getId(),
                        4,
                        1L
                )
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_reservation_capacity_allocations_reservation_bucket");
    }

    @Test
    @DisplayName("승인되지 않은 예약 상태는 DB 제약으로 거부한다")
    void rejectsUnapprovedReservationStatus() {
        // when & then
        assertThatThrownBy(() ->
                insertReservation(CONSUMER_ACCOUNT_ID, STORE_ID, "REQUESTED", null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_status");
    }

    @Test
    @DisplayName("예약 종결 상태와 종결 시각이 일치하지 않으면 거부한다")
    void rejectsInconsistentTerminalTimestamps() {
        // when & then
        assertThatThrownBy(() ->
                insertReservation(CONSUMER_ACCOUNT_ID, STORE_ID, "CANCELLED", null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_terminal_timestamps");
        assertThatThrownBy(() ->
                insertReservation(
                        CONSUMER_ACCOUNT_ID,
                        STORE_ID,
                        "CONFIRMED",
                        "2026-08-01 01:01:00.000000",
                        null
                ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_terminal_timestamps");
    }

    @Test
    @DisplayName("V15 형식의 기존 현지 시각 행은 임의 Instant나 offset을 만들지 않고 보존한다")
    void preservesLegacyLocalTimeRowsWithoutGuessingOffsets() {
        // when
        insertReservation(CONSUMER_ACCOUNT_ID, STORE_ID, "CONFIRMED", null, null);

        // then
        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                        SELECT start_time, end_time, start_at, service_end_at,
                               occupancy_end_at, time_zone_id_snapshot
                        FROM reservations
                        WHERE consumer_account_id = ? AND store_id = ?
                        """,
                CONSUMER_ACCOUNT_ID,
                STORE_ID
        );
        assertThat(row.get("start_time")).isNotNull();
        assertThat(row.get("end_time")).isNotNull();
        assertThat(row.get("start_at")).isNull();
        assertThat(row.get("service_end_at")).isNull();
        assertThat(row.get("occupancy_end_at")).isNull();
        assertThat(row.get("time_zone_id_snapshot")).isNull();

        Reservation legacyReservation = reservationRepository.findAll().getFirst();
        CustomerReservationTimeResponse customerTime =
                CustomerReservationTimeResponse.from(legacyReservation.getTimeSnapshot());
        assertThat(customerTime.serviceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(customerTime.timeStatus())
                .isEqualTo(CustomerReservationTimeStatus.LEGACY_UNRESOLVED);
        assertThat(customerTime.startAt()).isNull();
        assertThat(customerTime.serviceEndAt()).isNull();
        assertThat(customerTime.timeZoneId()).isNull();
    }

    @Test
    @DisplayName("신규 Instant 스냅샷은 DB에서도 분 단위 시작 시각만 허용한다")
    void rejectsSnapshotOutsideMinutePrecisionInDatabase() {
        Reservation saved = reservationRepository.saveAndFlush(reservation());

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        UPDATE reservations
                        SET start_at = TIMESTAMPADD(SECOND, 1, start_at),
                            service_end_at = TIMESTAMPADD(SECOND, 1, service_end_at),
                            occupancy_end_at = TIMESTAMPADD(SECOND, 1, occupancy_end_at)
                        WHERE reservation_id = ?
                        """,
                saved.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_time_snapshot");
    }

    @Test
    @DisplayName("신규 시간 스냅샷의 정책 소유 매장은 예약 매장과 같아야 한다")
    void rejectsSnapshotPolicyStoreMismatchInDatabase() {
        Reservation saved = reservationRepository.saveAndFlush(reservation());

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        UPDATE reservations
                        SET reservation_time_policy_store_id = ?
                        WHERE reservation_id = ?
                        """,
                STORE_ID + 1,
                saved.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_time_snapshot");
    }

    @Test
    @DisplayName("신규 Instant 스냅샷의 계산 근거 열은 DB에서도 모두 필수다")
    void rejectsIncompleteNewSnapshotInDatabase() {
        Reservation saved = reservationRepository.saveAndFlush(reservation());

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        UPDATE reservations
                        SET start_offset_seconds = NULL
                        WHERE reservation_id = ?
                        """,
                saved.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_time_snapshot");
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        UPDATE reservations
                        SET reservation_time_policy_store_id = NULL
                        WHERE reservation_id = ?
                        """,
                saved.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_time_snapshot");
    }

    @Test
    @DisplayName("예약과 수용량 버킷의 종료 시각은 DB에서도 시작 시각보다 늦어야 한다")
    void rejectsNonIncreasingServiceTimeInDatabase() {
        // when & then
        assertThatThrownBy(() -> insertReservationWithServiceTime(
                LocalTime.of(18, 0),
                LocalTime.of(18, 0)
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_time_snapshot");
        assertThatThrownBy(() -> insertCapacityBucketWithServiceTime(
                LocalTime.of(18, 0),
                LocalTime.of(17, 30)
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservation_capacity_buckets_service_time");
    }

    @Test
    @DisplayName("예약 인원 합계와 정책 버전은 DB에서도 유효해야 한다")
    void rejectsInvalidPartyAndPolicyVersion() {
        // when & then
        assertThatThrownBy(() -> jdbcTemplate.update(
                reservationInsertSql(0, 0, 0, 1L, 1L),
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                NOTIFICATION_TARGET_REFERENCE,
                "CONFIRMED",
                null,
                null
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_party_counts");
        assertThatThrownBy(() -> jdbcTemplate.update(
                reservationInsertSql(1, 0, 0, 0L, 1L),
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                NOTIFICATION_TARGET_REFERENCE,
                "CONFIRMED",
                null,
                null
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_policy_versions");
    }

    @Test
    @DisplayName("취소 정책 버전 0과 음수는 DB 제약으로 거부한다")
    void rejectsNonPositiveCancellationPolicyVersion() {
        Reservation saved = reservationRepository.saveAndFlush(reservation());

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        UPDATE reservations
                        SET cancellation_policy_version = 0
                        WHERE reservation_id = ?
                        """,
                saved.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_cancellation_policy_version");
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        UPDATE reservations
                        SET cancellation_policy_version = -1
                        WHERE reservation_id = ?
                        """,
                saved.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_cancellation_policy_version");
    }

    @Test
    @DisplayName("기존 예약은 취소 정책 버전 NULL을 보존할 수 있다")
    void allowsNullCancellationPolicyVersionForLegacyReservation() {
        insertReservation(CONSUMER_ACCOUNT_ID, STORE_ID, "CONFIRMED", null, null);

        Long cancellationPolicyVersion = jdbcTemplate.queryForObject(
                """
                        SELECT cancellation_policy_version
                        FROM reservations
                        WHERE consumer_account_id = ? AND store_id = ?
                        """,
                Long.class,
                CONSUMER_ACCOUNT_ID,
                STORE_ID
        );

        assertThat(cancellationPolicyVersion).isNull();
    }

    @Test
    @DisplayName("V22 예약은 최신 업그레이드 뒤 변경이나 정책 버전 backfill 없이 보존된다")
    void preservesLegacyReservationWhenUpgradingFromV22ToLatest() throws Exception {
        try (MySQLContainer legacyMysql =
                     new MySQLContainer(DockerImageName.parse("mysql:8.0.40"))) {
            legacyMysql.start();
            Flyway.configure()
                    .dataSource(
                            legacyMysql.getJdbcUrl(),
                            legacyMysql.getUsername(),
                            legacyMysql.getPassword()
                    )
                    .target(MigrationVersion.fromVersion("22"))
                    .load()
                    .migrate();

            String beforeMigration;
            try (Connection connection = legacyConnection(legacyMysql)) {
                insertLegacyParentsAndReservation(connection);
                beforeMigration = readLegacyReservation(connection);
            }

            Flyway upgradedFlyway = Flyway.configure()
                    .dataSource(
                            legacyMysql.getJdbcUrl(),
                            legacyMysql.getUsername(),
                            legacyMysql.getPassword()
                    )
                    .load();
            upgradedFlyway.migrate();

            try (Connection connection = legacyConnection(legacyMysql)) {
                assertThat(readLegacyReservation(connection)).isEqualTo(beforeMigration);
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("""
                             SELECT cancellation_policy_version
                             FROM reservations
                             WHERE reservation_id = 40001
                             """)) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getObject("cancellation_policy_version")).isNull();
                    assertThat(resultSet.next()).isFalse();
                }
            }
            assertThat(upgradedFlyway.info().applied())
                    .anyMatch(migration ->
                            "24".equals(String.valueOf(migration.getVersion()))
                                    && "V24__add_reservation_cancellation_contract.sql"
                                    .equals(migration.getScript()));
        }
    }

    @Test
    @DisplayName("V25 취소 예약은 V26 업그레이드 뒤 변경이나 감사 backfill 없이 보존된다")
    void preservesCancelledV25ReservationWithoutInventingAuditWhenUpgradingToLatest() throws Exception {
        try (MySQLContainer legacyMysql =
                     new MySQLContainer(DockerImageName.parse("mysql:8.0.40"))) {
            legacyMysql.start();
            Flyway.configure().dataSource(
                    legacyMysql.getJdbcUrl(),
                    legacyMysql.getUsername(),
                    legacyMysql.getPassword())
                    .target(MigrationVersion.fromVersion("25")).load().migrate();
            insertV25ParentsAndCancelledReservation(legacyMysql);

            String beforeMigration;
            Long beforeCancellationPolicyVersion;
            try (Connection connection = legacyConnection(legacyMysql)) {
                beforeMigration = readLegacyReservation(connection);
                beforeCancellationPolicyVersion = readCancellationPolicyVersion(connection);
            }

            Flyway upgraded = Flyway.configure().dataSource(
                    legacyMysql.getJdbcUrl(),
                    legacyMysql.getUsername(),
                    legacyMysql.getPassword()).load();
            upgraded.migrate();

            try (Connection connection = legacyConnection(legacyMysql)) {
                assertThat(readLegacyReservation(connection)).isEqualTo(beforeMigration);
                assertThat(readCancellationPolicyVersion(connection))
                        .isEqualTo(beforeCancellationPolicyVersion);
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("""
                             SELECT COUNT(*) AS audit_count
                             FROM reservation_cancellation_audits
                             WHERE reservation_id = 40001
                             """)) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getLong("audit_count")).isZero();
                    assertThat(resultSet.next()).isFalse();
                }
            }
            assertThat(upgraded.info().applied())
                    .anyMatch(migration ->
                            "26".equals(String.valueOf(migration.getVersion()))
                                    && "V26__create_reservation_cancellation_audits.sql"
                                    .equals(migration.getScript()));
        }
    }

    @Test
    @DisplayName("예약 당시 알림 대상 참조와 연락 가능 상태가 유효해야 한다")
    void rejectsInvalidContactSnapshot() {
        // when & then
        assertThatThrownBy(() -> insertReservationWithContactSnapshot(" ", true))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_notification_target_reference");
        assertThatThrownBy(() -> insertReservationWithContactSnapshot(
                NOTIFICATION_TARGET_REFERENCE,
                false
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_contact_available_at_confirmation");
    }

    @Test
    @DisplayName("한도 축소 뒤 기존 점유가 한도를 넘어도 수용량 이력은 저장된다")
    void allowsHistoricalOccupancyAboveReducedCapacity() {
        // given
        ReservationCapacityBucket historicalBucket = ReservationCapacityBucket.create(
                STORE_ID,
                LocalDate.of(2026, 8, 2),
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                2,
                1,
                4,
                2,
                1,
                2,
                true,
                2L
        );

        // when
        ReservationCapacityBucket saved =
                capacityBucketRepository.saveAndFlush(historicalBucket);

        // then
        assertThat(saved.getOccupiedPeople()).isGreaterThan(saved.getMaxPeople());
        assertThat(saved.getOccupiedTeams()).isGreaterThan(saved.getMaxTeams());
    }

    @Test
    @DisplayName("과거 예약과 배정이 참조하는 부모 행은 연쇄 삭제되지 않는다")
    void referencedParentsCannotBeDeleted() {
        // given
        Reservation savedReservation = reservationRepository.saveAndFlush(reservation());
        ReservationCapacityBucket savedBucket =
                capacityBucketRepository.saveAndFlush(capacityBucket(1L));
        capacityAllocationRepository.saveAndFlush(
                ReservationCapacityAllocation.allocate(
                        savedReservation.getId(),
                        savedBucket.getId(),
                        4,
                        1L
                )
        );

        // when & then
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM consumer_accounts WHERE consumer_account_id = ?",
                CONSUMER_ACCOUNT_ID
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM stores WHERE store_id = ?",
                STORE_ID
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM reservations WHERE reservation_id = ?",
                savedReservation.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        DELETE FROM reservation_capacity_buckets
                        WHERE reservation_capacity_bucket_id = ?
                        """,
                savedBucket.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("예약·수용량·배정 생성 트랜잭션이 실패하면 일부 행도 남지 않는다")
    void reservationCapacityAndAllocationRollBackTogether() {
        // when
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored -> {
            Reservation savedReservation = reservationRepository.saveAndFlush(reservation());
            ReservationCapacityBucket savedBucket =
                    capacityBucketRepository.saveAndFlush(capacityBucket(1L));
            capacityAllocationRepository.saveAndFlush(
                    ReservationCapacityAllocation.allocate(
                            savedReservation.getId(),
                            savedBucket.getId(),
                            4,
                            1L
                    )
            );
            throw new IllegalStateException("force rollback");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        // then
        assertThat(reservationRepository.count()).isZero();
        assertThat(capacityBucketRepository.count()).isZero();
        assertThat(capacityAllocationRepository.count()).isZero();
    }

    @Test
    @DisplayName("가용성 조회는 각 매장·날짜의 최신 정책 버킷만 구간 순서로 반환한다")
    void findsEachStoresLatestPolicyBucketsInServiceTimeOrder() {
        insertStore(SECOND_STORE_ID, "1234567891", "두번째 매장");
        capacityBucketRepository.saveAllAndFlush(List.of(
                capacityBucket(1L, LocalTime.of(18, 0), LocalTime.of(18, 30)),
                capacityBucket(2L, LocalTime.of(18, 30), LocalTime.of(19, 0)),
                capacityBucket(2L, LocalTime.of(18, 0), LocalTime.of(18, 30)),
                capacityBucket(
                        SECOND_STORE_ID,
                        4L,
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30)
                ),
                capacityBucket(
                        SECOND_STORE_ID,
                        5L,
                        LocalTime.of(18, 30),
                        LocalTime.of(19, 0)
                ),
                capacityBucket(
                        SECOND_STORE_ID,
                        5L,
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30)
                )
        ));

        List<ReservationCapacityBucket> buckets =
                capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                        List.of(STORE_ID, SECOND_STORE_ID),
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0)
                );

        assertThat(buckets)
                .extracting(
                        ReservationCapacityBucket::getStoreId,
                        ReservationCapacityBucket::getStartTime,
                        ReservationCapacityBucket::getEndTime,
                        ReservationCapacityBucket::getPolicyVersion
                )
                .containsExactly(
                        tuple(
                                STORE_ID,
                                LocalTime.of(18, 0),
                                LocalTime.of(18, 30),
                                2L
                        ),
                        tuple(
                                STORE_ID,
                                LocalTime.of(18, 30),
                                LocalTime.of(19, 0),
                                2L
                        ),
                        tuple(
                                SECOND_STORE_ID,
                                LocalTime.of(18, 0),
                                LocalTime.of(18, 30),
                                5L
                        ),
                        tuple(
                                SECOND_STORE_ID,
                                LocalTime.of(18, 30),
                                LocalTime.of(19, 0),
                                5L
                        )
                );
    }

    @Test
    @DisplayName("취소 복구용 원본 배정 조회는 버킷 PK 오름차순으로 반환한다")
    void allocationRepositoryContractReturnsBucketAscending() {
        // given
        Reservation savedReservation = reservationRepository.saveAndFlush(reservation());
        ReservationCapacityBucket firstBucket = capacityBucketRepository.saveAndFlush(
                capacityBucket(1L, LocalTime.of(18, 0), LocalTime.of(18, 30))
        );
        ReservationCapacityBucket secondBucket = capacityBucketRepository.saveAndFlush(
                capacityBucket(3L, LocalTime.of(18, 30), LocalTime.of(19, 0))
        );
        capacityAllocationRepository.saveAndFlush(ReservationCapacityAllocation.allocate(
                savedReservation.getId(),
                secondBucket.getId(),
                4,
                secondBucket.getPolicyVersion()
        ));
        capacityAllocationRepository.saveAndFlush(ReservationCapacityAllocation.allocate(
                savedReservation.getId(),
                firstBucket.getId(),
                4,
                firstBucket.getPolicyVersion()
        ));
        Method allocationMethod = requiredRepositoryMethod(
                capacityAllocationRepository,
                "findAllByReservationIdOrderByCapacityBucketIdAsc",
                1
        );

        // when
        @SuppressWarnings("unchecked")
        List<ReservationCapacityAllocation> allocations = transactionTemplate.execute(status ->
                (List<ReservationCapacityAllocation>) invokeRepositoryMethod(
                        allocationMethod,
                        capacityAllocationRepository,
                        savedReservation.getId()
                )
        );

        // then
        assertThat(allocations)
                .extracting(ReservationCapacityAllocation::getCapacityBucketId)
                .containsExactly(firstBucket.getId(), secondBucket.getId());
    }

    @Test
    @DisplayName("최신 수용량 정책 버전 조회는 매장과 업무 날짜 범위를 유지한다")
    void latestPolicyVersionRepositoryContractUsesStoreAndServiceDateScope() {
        // given
        capacityBucketRepository.saveAndFlush(
                capacityBucket(1L, LocalTime.of(18, 0), LocalTime.of(18, 30))
        );
        capacityBucketRepository.saveAndFlush(
                capacityBucket(3L, LocalTime.of(18, 30), LocalTime.of(19, 0))
        );
        insertStore(SECOND_STORE_ID, "1234567891", "두번째 매장");
        capacityBucketRepository.saveAndFlush(capacityBucket(
                SECOND_STORE_ID,
                9L,
                LocalTime.of(18, 0),
                LocalTime.of(18, 30)
        ));
        capacityBucketRepository.saveAndFlush(ReservationCapacityBucket.create(
                STORE_ID,
                LocalDate.of(2026, 8, 2),
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                20,
                5,
                0,
                0,
                1,
                8,
                true,
                8L
        ));
        Method latestVersionMethod = requiredRepositoryMethod(
                capacityBucketRepository,
                "findLatestPolicyVersion",
                2
        );

        // when
        @SuppressWarnings("unchecked")
        Optional<Long> latestVersion = transactionTemplate.execute(status ->
                (Optional<Long>) invokeRepositoryMethod(
                        latestVersionMethod,
                        capacityBucketRepository,
                        STORE_ID,
                        LocalDate.of(2026, 8, 1)
                )
        );

        // then
        assertThat(latestVersion).contains(3L);
    }

    @Test
    @DisplayName("취소 후보 ID 관찰은 엔티티를 잔류시키지 않아 후속 잠금이 동시 점유를 보존한다")
    void reservationRepositoryContractScalarObservationPreservesConcurrentOccupancy()
            throws Exception {
        ReservationCapacityBucket bucket = capacityBucket(
                3L,
                LocalTime.of(18, 0),
                LocalTime.of(18, 30)
        );
        bucket.occupy(3);
        ReservationCapacityBucket saved = capacityBucketRepository.saveAndFlush(bucket);
        Method observationMethod = requiredRepositoryMethod(
                capacityBucketRepository,
                "findLatestPolicyBucketIdsOverlapping",
                4
        );

        CountDownLatch observationCompleted = new CountDownLatch(1);
        CountDownLatch concurrentCommitCompleted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> cancellationWorker = executor.submit(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    @SuppressWarnings("unchecked")
                    List<Long> observedIds = (List<Long>) invokeRepositoryMethod(
                            observationMethod,
                            capacityBucketRepository,
                            List.of(STORE_ID),
                            LocalDate.of(2026, 8, 1),
                            LocalTime.of(18, 0),
                            LocalTime.of(18, 30)
                    );
                    assertThat(observedIds).containsExactly(saved.getId());
                    observationCompleted.countDown();
                    try {
                        if (!concurrentCommitCompleted.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("concurrent occupancy timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "concurrent occupancy wait interrupted", exception);
                    }

                    List<ReservationCapacityBucket> locked =
                            capacityBucketRepository.findAllByIdInForUpdate(observedIds);
                    assertThat(locked).singleElement().satisfies(latest -> {
                        assertThat(latest.getOccupiedPeople()).isEqualTo(5);
                        assertThat(latest.getOccupiedTeams()).isEqualTo(2);
                        latest.restore(3, 1);
                    });
                }));

        try {
            assertThat(observationCompleted.await(5, TimeUnit.SECONDS))
                    .as("scalar observation must complete before concurrent occupancy")
                    .isTrue();
            transactionTemplate.executeWithoutResult(status -> {
                List<ReservationCapacityBucket> locked =
                        capacityBucketRepository.findAllByIdInForUpdate(List.of(saved.getId()));
                assertThat(locked).singleElement().satisfies(current -> current.occupy(2));
            });
            concurrentCommitCompleted.countDown();
        } finally {
            concurrentCommitCompleted.countDown();
            try {
                cancellationWorker.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(jdbcTemplate.queryForMap(
                """
                        SELECT occupied_people, occupied_teams
                        FROM reservation_capacity_buckets
                        WHERE reservation_capacity_bucket_id = ?
                        """,
                saved.getId()
        ))
                .containsEntry("occupied_people", 2)
                .containsEntry("occupied_teams", 1);
    }

    @Test
    @DisplayName("앞 버킷 복구가 flush된 뒤 다음 버킷 underflow가 나면 전체 복구가 rollback된다")
    void reservationRepositoryContractRollsBackFlushedRestoreBeforeLaterUnderflow() {
        ReservationCapacityBucket first = capacityBucket(
                3L,
                LocalTime.of(18, 0),
                LocalTime.of(18, 30)
        );
        first.occupy(3);
        ReservationCapacityBucket second = capacityBucket(
                3L,
                LocalTime.of(18, 30),
                LocalTime.of(19, 0)
        );
        second.occupy(2);
        List<ReservationCapacityBucket> saved =
                capacityBucketRepository.saveAllAndFlush(List.of(first, second));
        List<Long> bucketIds = saved.stream()
                .map(ReservationCapacityBucket::getId)
                .sorted()
                .toList();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            List<ReservationCapacityBucket> locked =
                    capacityBucketRepository.findAllByIdInForUpdate(bucketIds);
            locked.getFirst().restore(3, 1);
            entityManager.flush();
            locked.get(1).restore(3, 1);
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("capacity occupancy cannot be restored below zero");

        assertThat(jdbcTemplate.queryForList(
                """
                        SELECT occupied_people, occupied_teams
                        FROM reservation_capacity_buckets
                        WHERE reservation_capacity_bucket_id IN (?, ?)
                        ORDER BY reservation_capacity_bucket_id ASC
                        """,
                bucketIds.get(0),
                bucketIds.get(1)
        ))
                .extracting(
                        row -> row.get("occupied_people"),
                        row -> row.get("occupied_teams")
                )
                .containsExactly(
                        tuple(3, 1),
                        tuple(2, 1)
                );
    }

    @Test
    @DisplayName("취소 복구용 수용량 잠금 조회는 PK 오름차순 행을 반환하고 쓰기 잠금을 유지한다")
    void orderedLockRepositoryContractReturnsSortedRowsAndHoldsWriteLock() throws Exception {
        // given
        ReservationCapacityBucket firstBucket = capacityBucketRepository.saveAndFlush(
                capacityBucket(1L, LocalTime.of(18, 0), LocalTime.of(18, 30))
        );
        ReservationCapacityBucket secondBucket = capacityBucketRepository.saveAndFlush(
                capacityBucket(3L, LocalTime.of(18, 30), LocalTime.of(19, 0))
        );
        Method orderedLockMethod = requiredRepositoryMethod(
                capacityBucketRepository,
                "findAllByIdInForUpdate",
                1
        );

        CountDownLatch repositoryLockHeld = new CountDownLatch(1);
        CountDownLatch releaseRepositoryLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> worker = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                @SuppressWarnings("unchecked")
                List<ReservationCapacityBucket> lockedBuckets =
                        (List<ReservationCapacityBucket>) invokeRepositoryMethod(
                                orderedLockMethod,
                                capacityBucketRepository,
                                List.of(secondBucket.getId(), firstBucket.getId())
                        );
                assertThat(lockedBuckets)
                        .extracting(ReservationCapacityBucket::getId)
                        .containsExactly(firstBucket.getId(), secondBucket.getId());
                repositoryLockHeld.countDown();
                try {
                    if (!releaseRepositoryLock.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("repository lock release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("repository lock wait interrupted", exception);
                }
            }));

            try {
                try (Connection independentConnection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                )) {
                    assertThat(repositoryLockHeld.await(5, TimeUnit.SECONDS))
                            .as("repository PESSIMISTIC_WRITE lock must be held before NOWAIT check")
                            .isTrue();
                    independentConnection.setAutoCommit(false);

                    SQLException lockFailure = null;
                    try (PreparedStatement statement = independentConnection.prepareStatement("""
                            SELECT reservation_capacity_bucket_id
                            FROM reservation_capacity_buckets
                            WHERE reservation_capacity_bucket_id = ?
                            FOR UPDATE NOWAIT
                            """)) {
                        statement.setLong(1, firstBucket.getId());
                        statement.executeQuery();
                    } catch (SQLException exception) {
                        lockFailure = exception;
                    }

                    assertThat((Throwable) lockFailure)
                            .as("MySQL must reject NOWAIT while the repository write lock is held")
                            .isNotNull();
                    assertThat(lockFailure.getErrorCode()).isEqualTo(3572);
                }
            } finally {
                releaseRepositoryLock.countDown();
                worker.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertReservationWriteLock(
            Method repositoryMethod,
            long reservationId,
            long actorScopeId
    ) throws Exception {
        CountDownLatch reservationLockHeld = new CountDownLatch(1);
        CountDownLatch releaseReservationLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> worker = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                @SuppressWarnings("unchecked")
                Optional<Reservation> lockedReservation =
                        (Optional<Reservation>) invokeRepositoryMethod(
                                repositoryMethod,
                                reservationRepository,
                                reservationId,
                                actorScopeId
                        );
                assertThat(lockedReservation).isPresent();
                reservationLockHeld.countDown();
                try {
                    if (!releaseReservationLock.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("reservation lock release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "reservation lock wait interrupted", exception);
                }
            }));

            try {
                assertThat(reservationLockHeld.await(5, TimeUnit.SECONDS))
                        .as("Reservation write lock must be held before NOWAIT check")
                        .isTrue();
                try (Connection independentConnection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                )) {
                    independentConnection.setAutoCommit(false);
                    SQLException lockFailure = null;
                    try (PreparedStatement statement = independentConnection.prepareStatement("""
                            SELECT reservation_id
                            FROM reservations
                            WHERE reservation_id = ?
                            FOR UPDATE NOWAIT
                            """)) {
                        statement.setLong(1, reservationId);
                        statement.executeQuery();
                    } catch (SQLException exception) {
                        lockFailure = exception;
                    }

                    assertThat((Throwable) lockFailure)
                            .as("MySQL must reject NOWAIT while Reservation write lock is held")
                            .isNotNull();
                    assertThat(lockFailure.getErrorCode()).isEqualTo(3572);
                }
            } finally {
                releaseReservationLock.countDown();
                worker.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Method requiredRepositoryMethod(
            Object repository,
            String methodName,
            int parameterCount
    ) {
        Optional<Method> method = Arrays.stream(repository.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .filter(candidate -> candidate.getParameterCount() == parameterCount)
                .findFirst();
        assertThat(method)
                .as("repository method %s must be present before its contract is invoked", methodName)
                .isPresent();
        return method.orElseThrow();
    }

    private Object invokeRepositoryMethod(Method method, Object repository, Object... arguments) {
        try {
            return method.invoke(repository, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("repository contract invocation failed", exception);
        }
    }

    private Connection legacyConnection(MySQLContainer legacyMysql) throws Exception {
        return DriverManager.getConnection(
                legacyMysql.getJdbcUrl(),
                legacyMysql.getUsername(),
                legacyMysql.getPassword()
        );
    }

    private void insertV25ParentsAndCancelledReservation(MySQLContainer legacyMysql) throws Exception {
        try (Connection connection = legacyConnection(legacyMysql);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO consumer_accounts (
                        consumer_account_id, email, password_hash, name, status, created_at, updated_at
                    ) VALUES (
                        10001, 'v25-reservation@example.com', 'hashed', 'V25 예약자', 'ACTIVE',
                        '2026-08-01 00:00:00.000000', '2026-08-01 00:00:00.000000'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO store_operator_accounts (
                        store_operator_account_id, email, password_hash, display_name, status, created_at, updated_at
                    ) VALUES (
                        20001, 'v25-reservation-owner@example.com', 'hashed', 'V25 운영자', 'ACTIVE',
                        '2026-08-01 00:00:00.000000', '2026-08-01 00:00:00.000000'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO stores (
                        store_id, store_operator_account_id, business_registration_number, business_type,
                        name, description, region, address, time_zone_id, applicant_self_attested_at,
                        required_terms_agreed_at, required_terms_version, store_category_code,
                        verification_status, operation_status, reservation_enabled, menu_hold_enabled,
                        pickup_enabled, created_at, updated_at
                    ) VALUES (
                        30001, 20001, '1234567890', 'CAFE', 'V25 매장', '', 'SEOUL', '서울시 중구',
                        'Asia/Seoul', '2026-08-01 00:00:00.000000', '2026-08-01 00:00:00.000000',
                        'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY', 'APPROVED', 'OPEN',
                        TRUE, TRUE, TRUE, '2026-08-01 00:00:00.000000', '2026-08-01 00:00:00.000000'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO reservations (
                        reservation_id, consumer_account_id, store_id, store_name_snapshot, service_date,
                        start_time, end_time, adult_count, child_count, infant_count,
                        notification_target_reference, contact_available_at_confirmation,
                        capacity_policy_version, reservation_policy_version, cancellation_policy_version,
                        status, created_at, cancelled_at, fulfilled_at
                    ) VALUES (
                        40001, 10001, 30001, 'V25 매장', '2026-08-01',
                        '18:00:00.000000', '19:00:00.000000', 2, 1, 1,
                        'consumer:10001:channel:primary', TRUE, 7, 9, 1,
                        'CANCELLED', '2026-08-01 01:00:00.000000',
                        '2026-08-01 02:00:00.000000', NULL
                    )
                    """);
        }
    }

    private void insertLegacyParentsAndReservation(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO consumer_accounts (
                        consumer_account_id,
                        email,
                        password_hash,
                        name,
                        status,
                        created_at,
                        updated_at
                    ) VALUES (
                        10001,
                        'legacy-reservation@example.com',
                        'hashed',
                        '기존 예약자',
                        'ACTIVE',
                        '2026-08-01 00:00:00.000000',
                        '2026-08-01 00:00:00.000000'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO store_operator_accounts (
                        store_operator_account_id,
                        email,
                        password_hash,
                        display_name,
                        status,
                        created_at,
                        updated_at
                    ) VALUES (
                        20001,
                        'legacy-reservation-owner@example.com',
                        'hashed',
                        '기존 운영자',
                        'ACTIVE',
                        '2026-08-01 00:00:00.000000',
                        '2026-08-01 00:00:00.000000'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO stores (
                        store_id,
                        store_operator_account_id,
                        business_registration_number,
                        business_type,
                        name,
                        description,
                        region,
                        address,
                        time_zone_id,
                        applicant_self_attested_at,
                        required_terms_agreed_at,
                        required_terms_version,
                        store_category_code,
                        verification_status,
                        operation_status,
                        pickup_eligibility,
                        reservation_enabled,
                        menu_hold_enabled,
                        pickup_enabled,
                        created_at,
                        updated_at
                    ) VALUES (
                        30001,
                        20001,
                        '1234567890',
                        'CAFE',
                        '기존 매장',
                        '',
                        'SEOUL',
                        '서울시 중구',
                        'Asia/Seoul',
                        '2026-08-01 00:00:00.000000',
                        '2026-08-01 00:00:00.000000',
                        'STORE_ONBOARDING_REQUIRED_TERMS_V1',
                        'CAFE_BAKERY',
                        'APPROVED',
                        'OPEN',
                        'ELIGIBLE',
                        TRUE,
                        TRUE,
                        TRUE,
                        '2026-08-01 00:00:00.000000',
                        '2026-08-01 00:00:00.000000'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO reservations (
                        reservation_id,
                        consumer_account_id,
                        store_id,
                        store_name_snapshot,
                        service_date,
                        start_time,
                        end_time,
                        adult_count,
                        child_count,
                        infant_count,
                        notification_target_reference,
                        contact_available_at_confirmation,
                        capacity_policy_version,
                        reservation_policy_version,
                        status,
                        created_at
                    ) VALUES (
                        40001,
                        10001,
                        30001,
                        '기존 매장',
                        '2026-08-01',
                        '18:00:00.000000',
                        '19:00:00.000000',
                        2,
                        1,
                        1,
                        'consumer:10001:channel:primary',
                        TRUE,
                        7,
                        9,
                        'CONFIRMED',
                        '2026-08-01 01:00:00.000000'
                    )
                    """);
        }
    }

    private String readLegacyReservation(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT JSON_OBJECT(
                         'reservation_id', reservation_id,
                         'consumer_account_id', consumer_account_id,
                         'store_id', store_id,
                         'store_name_snapshot', store_name_snapshot,
                         'service_date', service_date,
                         'start_at', start_at,
                         'service_end_at', service_end_at,
                         'occupancy_end_at', occupancy_end_at,
                         'time_zone_id_snapshot', time_zone_id_snapshot,
                         'start_offset_seconds', start_offset_seconds,
                         'service_end_offset_seconds', service_end_offset_seconds,
                         'occupancy_end_offset_seconds', occupancy_end_offset_seconds,
                         'slot_interval_minutes', slot_interval_minutes,
                         'service_duration_minutes', service_duration_minutes,
                         'turnover_duration_minutes', turnover_duration_minutes,
                         'reservation_time_policy_store_id',
                             reservation_time_policy_store_id,
                         'start_time', start_time,
                         'end_time', end_time,
                         'adult_count', adult_count,
                         'child_count', child_count,
                         'infant_count', infant_count,
                         'notification_target_reference', notification_target_reference,
                         'contact_available_at_confirmation',
                             contact_available_at_confirmation,
                         'capacity_policy_version', capacity_policy_version,
                         'reservation_policy_version', reservation_policy_version,
                         'status', status,
                         'created_at', created_at,
                         'cancelled_at', cancelled_at,
                         'fulfilled_at', fulfilled_at
                     ) AS reservation_snapshot
                     FROM reservations
                     WHERE reservation_id = 40001
                     """)) {
            assertThat(resultSet.next()).isTrue();
            String snapshot = resultSet.getString("reservation_snapshot");
            assertThat(resultSet.next()).isFalse();
            return snapshot;
        }
    }

    private Long readCancellationPolicyVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT cancellation_policy_version
                     FROM reservations
                     WHERE reservation_id = 40001
                     """)) {
            assertThat(resultSet.next()).isTrue();
            Long value = resultSet.getObject("cancellation_policy_version", Long.class);
            assertThat(resultSet.next()).isFalse();
            return value;
        }
    }

    private ReservationCancellationAudit cancellationAudit(long reservationId, String reason) {
        return ReservationCancellationAudit.recordSuccess(
                reservationId,
                ReservationCancellationActorType.STORE_OPERATOR,
                STORE_OPERATOR_ACCOUNT_ID,
                reason,
                CREATED_AT,
                CREATED_AT.plusSeconds(1),
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                1L,
                1L,
                cancellationAuditCommandId()
        );
    }

    private String cancellationAuditCommandId() {
        return "reservation-cancel:store-operator:20001:550e8400-e29b-41d4-a716-446655440000";
    }

    private void insertCancellationAudit(
            long reservationId,
            String actorType,
            long actorId,
            String reason,
            Instant requestedAt,
            Instant occurredAt,
            String beforeStatus,
            String afterStatus,
            long cancellationPolicyVersion,
            long capacityPolicyVersion,
            String commandId
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO reservation_cancellation_audits (
                            reservation_id, actor_type, actor_id, cancellation_reason, requested_at,
                            occurred_at, before_status, after_status, cancellation_policy_version,
                            capacity_policy_version, command_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reservationId,
                actorType,
                actorId,
                reason,
                Timestamp.from(requestedAt),
                Timestamp.from(occurredAt),
                beforeStatus,
                afterStatus,
                cancellationPolicyVersion,
                capacityPolicyVersion,
                commandId
        );
    }

    private Reservation reservation() {
        return reservation(CONSUMER_ACCOUNT_ID, STORE_ID);
    }

    private Reservation reservation(long consumerAccountId, long storeId) {
        return Reservation.confirm(
                consumerAccountId,
                storeId,
                "미리윰",
                reservationTimeSnapshot(storeId),
                PartyComposition.of(2, 1, 1),
                ReservationContactSnapshot.contactable(NOTIFICATION_TARGET_REFERENCE),
                1L,
                new ReservationCancellationPolicyVersion(1L),
                CREATED_AT
        );
    }

    private ReservationTimeSnapshot reservationTimeSnapshot() {
        return reservationTimeSnapshot(STORE_ID);
    }

    private ReservationTimeSnapshot reservationTimeSnapshot(long storeId) {
        ReservationTimePolicyVersion policy = timePolicy(storeId, 1L);
        policy.activate(CREATED_AT.minusSeconds(60), "예약 계산 정책");
        return ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 1, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );
    }

    private ReservationCapacityBucket capacityBucket(long policyVersion) {
        return capacityBucket(
                policyVersion,
                LocalTime.of(18, 0),
                LocalTime.of(18, 30)
        );
    }

    private ReservationCapacityBucket capacityBucket(
            long policyVersion,
            LocalTime startTime,
            LocalTime endTime
    ) {
        return capacityBucket(STORE_ID, policyVersion, startTime, endTime);
    }

    private ReservationCapacityBucket capacityBucket(
            long storeId,
            long policyVersion,
            LocalTime startTime,
            LocalTime endTime
    ) {
        return ReservationCapacityBucket.create(
                storeId,
                LocalDate.of(2026, 8, 1),
                startTime,
                endTime,
                20,
                5,
                0,
                0,
                1,
                8,
                true,
                policyVersion
        );
    }

    private ReservationTimePolicyVersion timePolicy(long versionNumber) {
        return timePolicy(STORE_ID, versionNumber);
    }

    private ReservationTimePolicyVersion timePolicy(long storeId, long versionNumber) {
        return ReservationTimePolicyVersion.createDraft(
                storeId,
                versionNumber,
                30,
                90,
                15
        );
    }

    private void insertStore(
            long storeId,
            String businessRegistrationNumber,
            String name
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO stores (
                            store_id,
                            store_operator_account_id,
                            business_registration_number,
                            business_type,
                            name,
                            description,
                            region,
                            address,
                            time_zone_id,
                            applicant_self_attested_at,
                            required_terms_agreed_at,
                            required_terms_version,
                            store_category_code,
                            verification_status,
                            operation_status,
                            reservation_enabled,
                            menu_hold_enabled,
                            pickup_enabled,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            ?, ?, ?, 'CAFE', ?, '', 'SEOUL', '서울시 중구',
                            'Asia/Seoul', NOW(6), NOW(6),
                            'STORE_ONBOARDING_REQUIRED_TERMS_V1',
                            'CAFE_BAKERY', 'APPROVED', 'OPEN',
                            TRUE, TRUE, TRUE, NOW(6), NOW(6)
                        )
                        """,
                storeId,
                STORE_OPERATOR_ACCOUNT_ID,
                businessRegistrationNumber,
                name
        );
    }

    private void insertReservation(
            long consumerAccountId,
            long storeId,
            String status,
            String cancelledAt,
            String fulfilledAt
    ) {
        jdbcTemplate.update(
                reservationInsertSql(2, 1, 1, 1L, 1L),
                consumerAccountId,
                storeId,
                NOTIFICATION_TARGET_REFERENCE,
                status,
                cancelledAt,
                fulfilledAt
        );
    }

    private void insertReservationWithServiceTime(
            LocalTime startTime,
            LocalTime endTime
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO reservations (
                            consumer_account_id,
                            store_id,
                            store_name_snapshot,
                            service_date,
                            start_time,
                            end_time,
                            adult_count,
                            child_count,
                            infant_count,
                            notification_target_reference,
                            contact_available_at_confirmation,
                            capacity_policy_version,
                            reservation_policy_version,
                            status,
                            created_at
                        )
                        VALUES (
                            ?, ?, '미리윰', '2026-08-01', ?, ?,
                            2, 1, 1, ?, TRUE, 1, 1, 'CONFIRMED',
                            '2026-08-01 01:00:00.000000'
                        )
                        """,
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                startTime,
                endTime,
                NOTIFICATION_TARGET_REFERENCE
        );
    }

    private void insertReservationWithContactSnapshot(
            String notificationTargetReference,
            boolean contactAvailableAtConfirmation
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO reservations (
                            consumer_account_id,
                            store_id,
                            store_name_snapshot,
                            service_date,
                            start_time,
                            end_time,
                            adult_count,
                            child_count,
                            infant_count,
                            notification_target_reference,
                            contact_available_at_confirmation,
                            capacity_policy_version,
                            reservation_policy_version,
                            status,
                            created_at
                        )
                        VALUES (
                            ?, ?, '미리윰', '2026-08-01',
                            '18:00:00.000000', '19:00:00.000000',
                            2, 1, 1, ?, ?, 1, 1, 'CONFIRMED',
                            '2026-08-01 01:00:00.000000'
                        )
                        """,
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                notificationTargetReference,
                contactAvailableAtConfirmation
        );
    }

    private void insertCapacityBucketWithServiceTime(
            LocalTime startTime,
            LocalTime endTime
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO reservation_capacity_buckets (
                            store_id,
                            service_date,
                            start_time,
                            end_time,
                            max_people,
                            max_teams,
                            occupied_people,
                            occupied_teams,
                            min_party_size,
                            max_party_size,
                            infants_allowed,
                            policy_version
                        )
                        VALUES (?, '2026-08-01', ?, ?, 20, 5, 0, 0, 1, 8, TRUE, 1)
                        """,
                STORE_ID,
                startTime,
                endTime
        );
    }

    private String reservationInsertSql(
            int adultCount,
            int childCount,
            int infantCount,
            long capacityPolicyVersion,
            long reservationPolicyVersion
    ) {
        return """
                INSERT INTO reservations (
                    consumer_account_id,
                    store_id,
                    store_name_snapshot,
                    service_date,
                    start_time,
                    end_time,
                    adult_count,
                    child_count,
                    infant_count,
                    notification_target_reference,
                    contact_available_at_confirmation,
                    capacity_policy_version,
                    reservation_policy_version,
                    status,
                    created_at,
                    cancelled_at,
                    fulfilled_at
                )
                VALUES (
                    ?, ?, '미리윰', '2026-08-01', '18:00:00.000000', '19:00:00.000000',
                    %d, %d, %d, ?, TRUE,
                    %d, %d, ?, '2026-08-01 01:00:00.000000', ?, ?
                )
                """.formatted(
                adultCount,
                childCount,
                infantCount,
                capacityPolicyVersion,
                reservationPolicyVersion
        );
    }
}
