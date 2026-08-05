package com.miriyum.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.dto.response.CustomerReservationTimeResponse;
import com.miriyum.domain.reservation.dto.response.CustomerReservationTimeStatus;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyAudit;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.flywaydb.core.Flyway;
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
                            pickup_eligibility,
                            reservation_enabled,
                            menu_hold_enabled,
                            pickup_enabled,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            ?, ?, '1234567890', 'CAFE', '미리윰', '', 'SEOUL', '서울시 중구',
                            'Asia/Seoul', NOW(6), NOW(6), 'STORE_ONBOARDING_REQUIRED_TERMS_V1',
                            'CAFE_BAKERY', 'APPROVED', 'OPEN', 'ELIGIBLE',
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

    private Reservation reservation() {
        return Reservation.confirm(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                "미리윰",
                reservationTimeSnapshot(),
                PartyComposition.of(2, 1, 1),
                ReservationContactSnapshot.contactable(NOTIFICATION_TARGET_REFERENCE),
                1L,
                CREATED_AT
        );
    }

    private ReservationTimeSnapshot reservationTimeSnapshot() {
        ReservationTimePolicyVersion policy = timePolicy(1L);
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
        return ReservationTimePolicyVersion.createDraft(
                STORE_ID,
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
                            pickup_eligibility,
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
                            'CAFE_BAKERY', 'APPROVED', 'OPEN', 'ELIGIBLE',
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
