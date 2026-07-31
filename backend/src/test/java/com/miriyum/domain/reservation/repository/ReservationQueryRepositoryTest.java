package com.miriyum.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 예약 조회 Repository가 실제 MySQL에서 계정·매장 소유 범위와 페이지 순서를 지키는지 검증한다.
 */
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
class ReservationQueryRepositoryTest {

    private static final long CONSUMER_ACCOUNT_ID = 10_001L;
    private static final long OTHER_CONSUMER_ACCOUNT_ID = 10_002L;
    private static final long STORE_OPERATOR_ACCOUNT_ID = 20_001L;
    private static final long OTHER_STORE_OPERATOR_ACCOUNT_ID = 20_002L;
    private static final long STORE_ID = 30_001L;
    private static final long OTHER_STORE_ID = 30_002L;
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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetRowsAndSeedParents() {
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");

        insertConsumerAccount(
                CONSUMER_ACCOUNT_ID,
                "reservation-query@example.com",
                "예약자"
        );
        insertConsumerAccount(
                OTHER_CONSUMER_ACCOUNT_ID,
                "other-reservation-query@example.com",
                "다른 예약자"
        );
        insertStoreOperatorAccount(
                STORE_OPERATOR_ACCOUNT_ID,
                "reservation-query-owner@example.com",
                "운영자"
        );
        insertStoreOperatorAccount(
                OTHER_STORE_OPERATOR_ACCOUNT_ID,
                "other-reservation-query-owner@example.com",
                "다른 운영자"
        );
        insertStore(
                STORE_ID,
                STORE_OPERATOR_ACCOUNT_ID,
                "1234567890",
                "첫 번째 매장"
        );
        insertStore(
                OTHER_STORE_ID,
                OTHER_STORE_OPERATOR_ACCOUNT_ID,
                "1234567891",
                "두 번째 매장"
        );
    }

    @Test
    @DisplayName("예약 ID와 소비자 계정 ID를 함께 조회해 다른 소비자에게 노출하지 않는다")
    void findsReservationOnlyInsideConsumerScope() {
        // given
        Reservation saved = saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT
        );

        // when & then
        assertThat(reservationRepository.findByIdAndConsumerAccountId(
                saved.getId(),
                CONSUMER_ACCOUNT_ID
        )).hasValueSatisfying(found ->
                assertThat(found.getId()).isEqualTo(saved.getId()));
        assertThat(reservationRepository.findByIdAndConsumerAccountId(
                saved.getId(),
                OTHER_CONSUMER_ACCOUNT_ID
        )).isEmpty();
    }

    @Test
    @DisplayName("예약 ID와 매장 ID를 함께 조회해 다른 매장에 노출하지 않는다")
    void findsReservationOnlyInsideStoreScope() {
        // given
        Reservation saved = saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT
        );

        // when & then
        assertThat(reservationRepository.findByIdAndStoreId(
                saved.getId(),
                STORE_ID
        )).hasValueSatisfying(found ->
                assertThat(found.getId()).isEqualTo(saved.getId()));
        assertThat(reservationRepository.findByIdAndStoreId(
                saved.getId(),
                OTHER_STORE_ID
        )).isEmpty();
    }

    @Test
    @DisplayName("소비자 예약 내역은 선택한 예약 상태만 반환한다")
    void filtersConsumerHistoryByStatus() {
        // given
        saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT
        );
        Reservation cancelled = reservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 2),
                CREATED_AT.plusSeconds(1)
        );
        cancelled.cancel(CREATED_AT.plusSeconds(2));
        reservationRepository.saveAndFlush(cancelled);

        // when
        Page<Reservation> result =
                reservationRepository.findAllByConsumerAccountIdAndStatus(
                        CONSUMER_ACCOUNT_ID,
                        ReservationStatus.CANCELLED,
                        PageRequest.of(0, 20, Sort.by("id"))
                );

        // then
        assertThat(result.getTotalElements()).isOne();
        assertThat(result.getContent())
                .extracting(Reservation::getStatus)
                .containsExactly(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("같은 정렬값은 예약 ID 보조 정렬로 안정적인 페이지를 만든다")
    void pagesConsumerHistoryDeterministically() {
        // given
        Reservation first = saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT
        );
        Reservation second = saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 2),
                CREATED_AT
        );
        Reservation third = saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 3),
                CREATED_AT
        );
        Sort sort = Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        );

        // when
        Page<Reservation> firstPage =
                reservationRepository.findAllByConsumerAccountId(
                        CONSUMER_ACCOUNT_ID,
                        PageRequest.of(0, 2, sort)
                );
        Page<Reservation> secondPage =
                reservationRepository.findAllByConsumerAccountId(
                        CONSUMER_ACCOUNT_ID,
                        PageRequest.of(1, 2, sort)
                );

        // then
        assertThat(firstPage.getContent())
                .extracting(Reservation::getId)
                .containsExactly(third.getId(), second.getId());
        assertThat(secondPage.getContent())
                .extracting(Reservation::getId)
                .containsExactly(first.getId());
    }

    @Test
    @DisplayName("예약이 없는 소비자 페이지는 빈 결과와 0개 합계를 반환한다")
    void returnsEmptyPageInsideConsumerScope() {
        // given
        Pageable pageable = PageRequest.of(
                0,
                20,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        // when
        Page<Reservation> result =
                reservationRepository.findAllByConsumerAccountId(
                        OTHER_CONSUMER_ACCOUNT_ID,
                        pageable
                );

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("매장 예약 목록은 다른 매장을 제외하고 서비스 날짜와 예약 ID 순서를 지킨다")
    void listsReservationsOnlyInsideStoreScopeDeterministically() {
        // given
        Reservation first = saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT
        );
        Reservation second = saveReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT.plusSeconds(1)
        );
        Reservation third = saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 2),
                CREATED_AT.plusSeconds(2)
        );
        saveReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                OTHER_STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT.plusSeconds(3)
        );
        saveReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                OTHER_STORE_ID,
                LocalDate.of(2026, 8, 2),
                CREATED_AT.plusSeconds(4)
        );
        Sort sort = Sort.by(
                Sort.Order.asc("serviceDate"),
                Sort.Order.asc("id")
        );

        // when
        Page<Reservation> result = reservationRepository.findAllByStoreId(
                STORE_ID,
                PageRequest.of(0, 20, sort)
        );

        // then
        assertThat(result.getContent())
                .extracting(Reservation::getId)
                .containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(result.getContent())
                .extracting(Reservation::getStoreId)
                .containsOnly(STORE_ID);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("매장 예약 목록은 대상 매장의 선택한 서비스 날짜만 반환한다")
    void filtersStoreReservationsByServiceDate() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 1);
        Reservation expected = saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                serviceDate,
                CREATED_AT
        );
        saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                serviceDate.plusDays(1),
                CREATED_AT.plusSeconds(1)
        );
        saveReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                OTHER_STORE_ID,
                serviceDate,
                CREATED_AT.plusSeconds(2)
        );

        // when
        Page<Reservation> result =
                reservationRepository.findAllByStoreIdAndServiceDate(
                        STORE_ID,
                        serviceDate,
                        PageRequest.of(0, 20, Sort.by("id"))
                );

        // then
        assertThat(result.getContent())
                .extracting(Reservation::getId)
                .containsExactly(expected.getId());
        assertThat(result.getContent())
                .extracting(Reservation::getStoreId)
                .containsOnly(STORE_ID);
    }

    @Test
    @DisplayName("매장 예약 목록은 대상 매장의 선택한 예약 상태만 반환한다")
    void filtersStoreReservationsByStatus() {
        // given
        saveReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT
        );
        Reservation expected = saveCancelledReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                STORE_ID,
                LocalDate.of(2026, 8, 2),
                CREATED_AT.plusSeconds(1)
        );
        saveCancelledReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                OTHER_STORE_ID,
                LocalDate.of(2026, 8, 2),
                CREATED_AT.plusSeconds(3)
        );

        // when
        Page<Reservation> result =
                reservationRepository.findAllByStoreIdAndStatus(
                        STORE_ID,
                        ReservationStatus.CANCELLED,
                        PageRequest.of(0, 20, Sort.by("id"))
                );

        // then
        assertThat(result.getContent())
                .extracting(Reservation::getId)
                .containsExactly(expected.getId());
        assertThat(result.getContent())
                .extracting(Reservation::getStoreId)
                .containsOnly(STORE_ID);
        assertThat(result.getContent())
                .extracting(Reservation::getStatus)
                .containsOnly(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("매장 예약 목록은 대상 매장의 서비스 날짜와 예약 상태를 함께 적용한다")
    void filtersStoreReservationsByServiceDateAndStatus() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 1);
        Reservation expected = saveCancelledReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                serviceDate,
                CREATED_AT
        );
        saveCancelledReservation(
                CONSUMER_ACCOUNT_ID,
                STORE_ID,
                serviceDate.plusDays(1),
                CREATED_AT.plusSeconds(2)
        );
        saveReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                STORE_ID,
                serviceDate,
                CREATED_AT.plusSeconds(4)
        );
        saveCancelledReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                OTHER_STORE_ID,
                serviceDate,
                CREATED_AT.plusSeconds(5)
        );

        // when
        Page<Reservation> result =
                reservationRepository.findAllByStoreIdAndServiceDateAndStatus(
                        STORE_ID,
                        serviceDate,
                        ReservationStatus.CANCELLED,
                        PageRequest.of(0, 20, Sort.by("id"))
                );

        // then
        assertThat(result.getContent())
                .extracting(Reservation::getId)
                .containsExactly(expected.getId());
        assertThat(result.getContent())
                .extracting(Reservation::getStoreId)
                .containsOnly(STORE_ID);
        assertThat(result.getContent())
                .extracting(Reservation::getServiceDate, Reservation::getStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        serviceDate,
                        ReservationStatus.CANCELLED
                ));
    }

    @Test
    @DisplayName("예약이 없는 매장 페이지는 다른 매장 예약을 노출하지 않고 빈 결과를 반환한다")
    void returnsEmptyPageInsideStoreScope() {
        // given
        saveReservation(
                OTHER_CONSUMER_ACCOUNT_ID,
                OTHER_STORE_ID,
                LocalDate.of(2026, 8, 1),
                CREATED_AT
        );
        Pageable pageable = PageRequest.of(
                0,
                20,
                Sort.by(
                        Sort.Order.asc("serviceDate"),
                        Sort.Order.asc("id")
                )
        );

        // when
        Page<Reservation> result =
                reservationRepository.findAllByStoreId(STORE_ID, pageable);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    private Reservation saveReservation(
            long consumerAccountId,
            long storeId,
            LocalDate serviceDate,
            Instant createdAt
    ) {
        return reservationRepository.saveAndFlush(
                reservation(consumerAccountId, storeId, serviceDate, createdAt)
        );
    }

    private Reservation saveCancelledReservation(
            long consumerAccountId,
            long storeId,
            LocalDate serviceDate,
            Instant createdAt
    ) {
        Reservation reservation = reservation(
                consumerAccountId,
                storeId,
                serviceDate,
                createdAt
        );
        reservation.cancel(createdAt.plusSeconds(1));
        return reservationRepository.saveAndFlush(reservation);
    }

    private Reservation reservation(
            long consumerAccountId,
            long storeId,
            LocalDate serviceDate,
            Instant createdAt
    ) {
        return Reservation.confirm(
                consumerAccountId,
                storeId,
                storeId == STORE_ID ? "첫 번째 매장" : "두 번째 매장",
                serviceDate,
                LocalTime.of(18, 0),
                LocalTime.of(19, 0),
                PartyComposition.of(2, 0, 0),
                1L,
                1L,
                createdAt
        );
    }

    private void insertConsumerAccount(long id, String email, String name) {
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
                        VALUES (?, ?, 'hashed', ?, 'ACTIVE', NOW(6), NOW(6))
                        """,
                id,
                email,
                name
        );
    }

    private void insertStoreOperatorAccount(long id, String email, String displayName) {
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
                        VALUES (?, ?, 'hashed', ?, 'ACTIVE', NOW(6), NOW(6))
                        """,
                id,
                email,
                displayName
        );
    }

    private void insertStore(
            long storeId,
            long storeOperatorAccountId,
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
                            'CAFE_BAKERY', 'APPROVED', 'OPEN', 'ELIGIBLE',
                            TRUE, TRUE, TRUE, NOW(6), NOW(6)
                        )
                        """,
                storeId,
                storeOperatorAccountId,
                businessRegistrationNumber,
                name
        );
    }
}
