package com.miriyum.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class ReservationMigrationTest {

    private static final long CONSUMER_ACCOUNT_ID = 10_001L;
    private static final long STORE_OPERATOR_ACCOUNT_ID = 20_001L;
    private static final long STORE_ID = 30_001L;
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T01:00:00Z");

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
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Autowired
    private ReservationCapacityAllocationRepository capacityAllocationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetRowsAndSeedParents() {
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
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
                            'CAFE_BAKERY', 'APPROVED', 'OPEN', 'ELIGIBLE',
                            TRUE, TRUE, TRUE, NOW(6), NOW(6)
                        )
                        """,
                STORE_ID,
                STORE_OPERATOR_ACCOUNT_ID
        );
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
        assertThat(foundBucket.getPolicyVersion()).isEqualTo(1L);
        assertThat(foundAllocation.getReservationId()).isEqualTo(savedReservation.getId());
        assertThat(foundAllocation.getCapacityBucketId()).isEqualTo(savedBucket.getId());
        assertThat(foundAllocation.getOccupiedTeams()).isOne();
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
    @DisplayName("예약과 수용량 버킷의 종료 시각은 DB에서도 시작 시각보다 늦어야 한다")
    void rejectsNonIncreasingServiceTimeInDatabase() {
        // when & then
        assertThatThrownBy(() -> insertReservationWithServiceTime(
                LocalTime.of(18, 0),
                LocalTime.of(18, 0)
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_service_time");
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
                "CONFIRMED",
                null,
                null
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reservations_policy_versions");
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

    private Reservation reservation() {
        return Reservation.confirm(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                "미리윰",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(19, 0),
                PartyComposition.of(2, 1, 1),
                1L,
                1L,
                CREATED_AT
        );
    }

    private ReservationCapacityBucket capacityBucket(long policyVersion) {
        return ReservationCapacityBucket.create(
                STORE_ID,
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
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
                            capacity_policy_version,
                            reservation_policy_version,
                            status,
                            created_at
                        )
                        VALUES (
                            ?, ?, '미리윰', '2026-08-01', ?, ?,
                            2, 1, 1, 1, 1, 'CONFIRMED', '2026-08-01 01:00:00.000000'
                        )
                        """,
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                startTime,
                endTime
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
                    capacity_policy_version,
                    reservation_policy_version,
                    status,
                    created_at,
                    cancelled_at,
                    fulfilled_at
                )
                VALUES (
                    ?, ?, '미리윰', '2026-08-01', '18:00:00.000000', '19:00:00.000000',
                    %d, %d, %d, %d, %d, ?, '2026-08-01 01:00:00.000000', ?, ?
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
