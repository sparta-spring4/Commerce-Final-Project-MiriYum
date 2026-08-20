package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.ReservationDepositCalculationSnapshot;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.Calculation;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.ItemSnapshot;
import com.miriyum.domain.schedule.closure.service.RegularClosureActivationJob;
import com.miriyum.domain.schedule.service.StoreScheduleActivationJob;
import com.miriyum.global.idempotency.IdempotencyKey;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.task.scheduling.enabled=false",
            "miriyum.reservation.deposit-worker.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
@Import(ReservationDepositProcessRuntimeIT.MutableClockConfig.class)
class ReservationDepositProcessRuntimeIT {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-20T09:10:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationDepositProcessRepository processRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ReservationDepositProcessService processService;

    @Autowired
    private ReservationDepositProcessCommandFacade processCommandFacade;

    @Autowired
    private ReservationHoldCommandFacade holdCommandFacade;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MutableClock clock;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private StoreScheduleActivationJob storeScheduleActivationJob;

    @MockitoBean
    private RegularClosureActivationJob regularClosureActivationJob;

    @BeforeEach
    void resetDatabase() {
        clock.set(REQUESTED_AT);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_deposit_finalization_allocation_failure");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_refund_obligations");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_cause_audits");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_calculation_items");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_processes");
        jdbcTemplate.execute("DELETE FROM reservation_hold_transition_audits");
        jdbcTemplate.execute("DELETE FROM reservation_hold_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_allocations");
        jdbcTemplate.execute("DELETE FROM reservations");
        jdbcTemplate.execute("DELETE FROM reservation_holds");
        jdbcTemplate.execute("DELETE FROM reservation_capacity_buckets");
        jdbcTemplate.execute("DELETE FROM idempotency_commands");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
    }

    @Test
    @Transactional
    void persistsProcessAndImmutableCalculationItemsUsingFlywaySchema() {
        seedHoldOwnerAndStore();
        Calculation calculation = new Calculation(
                91L,
                20,
                1L,
                2,
                4_000L,
                "KRW",
                13L,
                40_000L,
                2,
                List.of(
                        new ItemSnapshot("101", 4, 18_000),
                        new ItemSnapshot("102", 8, 22_000)));
        PaymentPreparation payment = new PaymentPreparation(
                "900000000000000077",
                "deposit-runtime-portone",
                "미리윰 식당 예약금",
                4_000L,
                "KRW",
                EXPIRES_AT,
                PaymentStatus.READY);

        ReservationDepositProcess saved = processRepository.saveAndFlush(
                ReservationDepositProcess.awaitingPayment(
                        40_077L,
                        10_077L,
                        EXPIRES_AT,
                        calculation,
                        payment,
                        REQUESTED_AT));
        long processId = saved.getId();
        entityManager.clear();

        ReservationDepositProcess found = processRepository.findById(processId).orElseThrow();

        assertThat(found.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
        assertThat(found.getReconciliationNextAttemptAt()).isEqualTo(REQUESTED_AT);
        assertThat(found.isResourcesProtected()).isFalse();
        assertThat(found.getCalculationSnapshot().getCurrency()).isEqualTo("KRW");
        assertThat(found.getCalculationSnapshot().getItems())
                .extracting(ReservationDepositCalculationSnapshot.Item::getMenuId)
                .containsExactlyInAnyOrder("101", "102");
    }

    @Test
    void exactLeaseExpiryReclaimsAndFencesTheStaleProcessWorker() {
        seedHoldOwnerAndStore();
        ReservationDepositProcess saved = processRepository.saveAndFlush(
                ReservationDepositProcess.awaitingPayment(
                        40_077L,
                        10_077L,
                        EXPIRES_AT,
                        new Calculation(
                                91L,
                                20,
                                1L,
                                2,
                                4_000L,
                                "KRW",
                                13L,
                                40_000L,
                                1,
                                List.of(new ItemSnapshot("101", 4, 40_000))),
                        new PaymentPreparation(
                                "900000000000000077",
                                "deposit-runtime-portone",
                                "미리윰 식당 예약금",
                                4_000L,
                                "KRW",
                                EXPIRES_AT,
                                PaymentStatus.READY),
                        REQUESTED_AT));

        ReservationDepositProcessService.Claim stale =
                processService.claimDue("process-worker-a", 1).getFirst();
        clock.advance(Duration.ofSeconds(30));
        ReservationDepositProcessService.Claim current =
                processService.claimDue("process-worker-b", 1).getFirst();

        assertThat(current.processId()).isEqualTo(saved.getId());
        assertThat(current.token()).isGreaterThan(stale.token());
        assertThat(processService.reconcileClaimed(stale)).isFalse();
        org.mockito.Mockito.when(paymentService.getOwnedPayment(
                "900000000000000077", "10077")).thenReturn(new PaymentResult(
                        "900000000000000077",
                        "40077",
                        4_000L,
                        0L,
                        4_000L,
                        "KRW",
                        PaymentStatus.READY,
                        PaymentAttemptStatus.NOT_STARTED,
                        REQUESTED_AT,
                        null,
                        clock.instant(),
                        List.of()));

        assertThat(processService.reconcileClaimed(current)).isTrue();

        ReservationDepositProcess found = processRepository.findById(saved.getId())
                .orElseThrow();
        assertThat(found.getStatus())
                .isEqualTo(ReservationDepositProcessStatus.AWAITING_PAYMENT);
        assertThat(found.getReconciliationLeaseOwner()).isNull();
        assertThat(found.getReconciliationLeaseUntil()).isNull();
        assertThat(found.getReconciliationNextAttemptAt())
                .isEqualTo(clock.instant().plusSeconds(5));
        assertThat(found.getReconciliationClaimToken()).isEqualTo(current.token());
    }

    @Test
    @DisplayName("만료 정각 Hold 만료와 PAID 확정 경합은 부활 없이 한 번의 전액 환불 의무로 수렴한다")
    void holdExpirationAndPaidFinalizationRaceNeverResurrectsReservation() throws Exception {
        seedHoldOwnerAndStore();
        seedCapacityForHold();
        ReservationDepositProcess saved = processRepository.saveAndFlush(
                ReservationDepositProcess.awaitingPayment(
                        40_077L,
                        10_077L,
                        EXPIRES_AT,
                        new Calculation(
                                91L,
                                20,
                                1L,
                                2,
                                4_000L,
                                "KRW",
                                13L,
                                40_000L,
                                1,
                                List.of(new ItemSnapshot("101", 4, 40_000))),
                        new PaymentPreparation(
                                "900000000000000077",
                                "deposit-runtime-portone",
                                "미리윰 식당 예약금",
                                4_000L,
                                "KRW",
                                EXPIRES_AT,
                                PaymentStatus.READY),
                        REQUESTED_AT));
        long processId = saved.getId();
        Instant paidAt = EXPIRES_AT.minusSeconds(1);
        org.mockito.Mockito.when(paymentService.getOwnedPayment(
                "900000000000000077", "10077")).thenReturn(new PaymentResult(
                        "900000000000000077",
                        "40077",
                        4_000L,
                        0L,
                        4_000L,
                        "KRW",
                        PaymentStatus.PAID,
                        PaymentAttemptStatus.PAID,
                        REQUESTED_AT,
                        paidAt,
                        EXPIRES_AT,
                        List.of()));
        clock.set(EXPIRES_AT);

        RacePair<ReservationHoldContracts.Result, ReservationDepositCommandResult> results =
                invokePairWhileHoldLocked(
                        () -> holdCommandFacade.transition(
                                new ReservationHoldContracts.TransitionCommand(
                                        40_077L,
                                        ReservationHoldStatus.EXPIRED,
                                        "reservation-hold-expire:40077",
                                        "SYSTEM",
                                        null,
                                        EXPIRES_AT,
                                        null)),
                        () -> processCommandFacade.finalizeRequest(
                                10_077L,
                                processId,
                                IdempotencyKey.parse(
                                        "550e8400-e29b-41d4-a716-446655440077")));

        assertThat(results.first().status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(results.second().httpStatus()).isEqualTo(202);
        assertThat(results.second().reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.COMPENSATION_REQUIRED);
        assertThat(results.second().reservationRequest().reservation()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_holds WHERE reservation_hold_id = 40077",
                String.class)).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE reservation_capacity_bucket_id = 50077
                """))
                .containsEntry("occupied_people", 0)
                .containsEntry("occupied_teams", 0);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_hold_transition_audits
                 WHERE reservation_hold_id = 40077
                   AND command_id = 'reservation-hold-expire:40077'
                   AND before_status = 'ACTIVE'
                   AND after_status = 'EXPIRED'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, final_reservation_id
                  FROM reservation_deposit_processes
                 WHERE reservation_deposit_process_id = ?
                """, processId))
                .containsEntry("status", "COMPENSATION_REQUIRED")
                .containsEntry("final_reservation_id", null);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_deposit_cause_audits
                 WHERE reservation_deposit_process_id = ?
                   AND cause_code = 'UNPROTECTED_LATE_PAID'
                """, Integer.class, processId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_deposit_refund_obligations
                 WHERE reservation_deposit_process_id = ?
                   AND payment_id = '900000000000000077'
                   AND reason_code = 'FULL_DEPOSIT_COMPENSATION'
                   AND status = 'REQUIRED'
                """, Integer.class, processId)).isOne();
    }

    @Test
    @DisplayName("paidAt이 expiresAt과 같으면 확정하지 않고 전액 환불 의무로 전이한다")
    void paidAtExactExpiryRequiresCompensationInsteadOfFinalization() {
        seedHoldOwnerAndStore();
        seedCapacityForHold();
        ReservationDepositProcess saved = seedAwaitingProcess();
        stubPaidPayment(EXPIRES_AT, EXPIRES_AT);
        clock.set(EXPIRES_AT);

        ReservationDepositCommandResult result = processCommandFacade.finalizeRequest(
                10_077L,
                saved.getId(),
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440078"));

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.reservationRequest().status())
                .isEqualTo(ReservationDepositProcessStatus.COMPENSATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_holds WHERE reservation_hold_id = 40077",
                String.class)).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE reservation_capacity_bucket_id = 50077
                """))
                .containsEntry("occupied_people", 0)
                .containsEntry("occupied_teams", 0);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_deposit_cause_audits
                 WHERE reservation_deposit_process_id = ?
                   AND cause_code = 'PAYMENT_PAID_AT_OR_AFTER_EXPIRY'
                   AND paid_at = '2026-08-20 09:10:00.000000'
                """, Integer.class, saved.getId())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM reservation_deposit_refund_obligations
                 WHERE reservation_deposit_process_id = ?
                   AND status = 'REQUIRED'
                """, Integer.class, saved.getId())).isOne();
    }

    @Test
    @DisplayName("최종 Reservation allocation 실패는 process·Hold·멱등 효과를 롤백하고 같은 키 재시도를 허용한다")
    void finalizationAllocationFailureRollsBackAllEffectsAndAllowsSameKeyRetry() {
        seedHoldOwnerAndStore();
        seedCapacityForHold();
        ReservationDepositProcess saved = seedAwaitingProcess();
        stubPaidPayment(EXPIRES_AT.minusSeconds(1), EXPIRES_AT.minusSeconds(2));
        clock.set(EXPIRES_AT.minusSeconds(2));
        IdempotencyKey key = IdempotencyKey.parse(
                "550e8400-e29b-41d4-a716-446655440079");

        try {
            jdbcTemplate.execute("""
                    CREATE TRIGGER trg_deposit_finalization_allocation_failure
                    BEFORE INSERT ON reservation_capacity_allocations
                    FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'reservation deposit allocation failure'
                    """);

            assertThatThrownBy(() -> processCommandFacade.finalizeRequest(
                    10_077L,
                    saved.getId(),
                    key))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasStackTraceContaining("reservation deposit allocation failure");
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER IF EXISTS trg_deposit_finalization_allocation_failure");
        }

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, final_reservation_id
                  FROM reservation_deposit_processes
                 WHERE reservation_deposit_process_id = ?
                """, saved.getId()))
                .containsEntry("status", "AWAITING_PAYMENT")
                .containsEntry("final_reservation_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_holds WHERE reservation_hold_id = 40077",
                String.class)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_capacity_allocations",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_hold_transition_audits",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT occupied_people, occupied_teams
                  FROM reservation_capacity_buckets
                 WHERE reservation_capacity_bucket_id = 50077
                """))
                .containsEntry("occupied_people", 2)
                .containsEntry("occupied_teams", 1);

        ReservationDepositCommandResult retry = processCommandFacade.finalizeRequest(
                10_077L,
                saved.getId(),
                key);

        assertThat(retry.httpStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_deposit_processes "
                        + "WHERE reservation_deposit_process_id = ?",
                String.class,
                saved.getId())).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT final_reservation_id FROM reservation_deposit_processes "
                        + "WHERE reservation_deposit_process_id = ?",
                Long.class,
                saved.getId())).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM reservation_holds WHERE reservation_hold_id = 40077",
                String.class)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancellation_policy_version FROM reservations",
                Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_capacity_allocations",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_commands "
                        + "WHERE processing_status = 'SUCCEEDED'",
                Integer.class)).isOne();
    }

    private ReservationDepositProcess seedAwaitingProcess() {
        return processRepository.saveAndFlush(ReservationDepositProcess.awaitingPayment(
                40_077L,
                10_077L,
                EXPIRES_AT,
                new Calculation(
                        91L,
                        20,
                        1L,
                        2,
                        4_000L,
                        "KRW",
                        13L,
                        40_000L,
                        1,
                        List.of(new ItemSnapshot("101", 4, 40_000))),
                new PaymentPreparation(
                        "900000000000000077",
                        "deposit-runtime-portone",
                        "미리윰 식당 예약금",
                        4_000L,
                        "KRW",
                        EXPIRES_AT,
                        PaymentStatus.READY),
                REQUESTED_AT));
    }

    private void stubPaidPayment(Instant paidAt, Instant observedAt) {
        org.mockito.Mockito.when(paymentService.getOwnedPayment(
                "900000000000000077", "10077")).thenReturn(new PaymentResult(
                        "900000000000000077",
                        "40077",
                        4_000L,
                        0L,
                        4_000L,
                        "KRW",
                        PaymentStatus.PAID,
                        PaymentAttemptStatus.PAID,
                        REQUESTED_AT,
                        paidAt,
                        observedAt,
                        List.of()));
    }

    private void seedHoldOwnerAndStore() {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name,
                    status, created_at, updated_at
                ) VALUES (
                    10077, 'deposit-runtime@example.com', 'hash', '예약금 회원',
                    'ACTIVE', NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name,
                    status, created_at, updated_at
                ) VALUES (
                    20077, 'deposit-runtime-owner@example.com', 'hash', '예약금 운영자',
                    'ACTIVE', NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO stores (
                    store_id, store_operator_account_id, business_registration_number,
                    business_type, name, description, region, address, time_zone_id,
                    applicant_self_attested_at, required_terms_agreed_at,
                    required_terms_version, store_category_code, verification_status,
                    operation_status, reservation_enabled, menu_hold_enabled,
                    pickup_enabled, created_at, updated_at
                ) VALUES (
                    30077, 20077, '9876543277', 'CAFE', '예약금 런타임 매장', '',
                    'SEOUL', '서울시 중구', 'Asia/Seoul', NOW(6), NOW(6),
                    'STORE_ONBOARDING_REQUIRED_TERMS_V1', 'CAFE_BAKERY',
                    'APPROVED', 'OPEN', TRUE, TRUE, TRUE, NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO reservation_holds (
                    reservation_hold_id, consumer_account_id, store_id,
                    store_name_snapshot, service_date, start_at, service_end_at,
                    occupancy_end_at, time_zone_id_snapshot, start_offset_seconds,
                    service_end_offset_seconds, occupancy_end_offset_seconds,
                    slot_interval_minutes, service_duration_minutes,
                    turnover_duration_minutes, reservation_time_policy_store_id,
                    reservation_policy_version, adult_count, child_count, infant_count,
                    notification_target_reference, contact_available_at_confirmation,
                    capacity_policy_version, cancellation_policy_version, status,
                    status_version, creation_command_id, created_at, expires_at
                ) VALUES (
                    40077, 10077, 30077, '예약금 런타임 매장', '2026-08-21',
                    '2026-08-21 09:00:00.000000', '2026-08-21 10:00:00.000000',
                    '2026-08-21 10:10:00.000000', 'UTC', 0, 0,
                    0, 10, 60, 10, 30077, 9, 2, 0, 0,
                    'consumer:10077:channel:primary', TRUE, 7, 2, 'ACTIVE', 0,
                    'deposit-runtime-hold-77',
                    '2026-08-20 09:00:00.000000',
                    '2026-08-20 09:10:00.000000'
                )
                """);
    }

    private void seedCapacityForHold() {
        jdbcTemplate.update("""
                INSERT INTO reservation_capacity_buckets (
                    reservation_capacity_bucket_id, store_id, service_date,
                    start_time, end_time, max_people, max_teams,
                    occupied_people, occupied_teams, min_party_size,
                    max_party_size, infants_allowed, policy_version
                ) VALUES (
                    50077, 30077, '2026-08-21', '09:00:00.000000',
                    '10:10:00.000000', 10, 5, 2, 1, 1, 10, TRUE, 7
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO reservation_hold_capacity_allocations (
                    reservation_hold_id, reservation_capacity_bucket_id,
                    occupied_people, occupied_teams, capacity_policy_version
                ) VALUES (40077, 50077, 2, 1, 7)
                """);
    }

    private <F, S> RacePair<F, S> invokePairWhileHoldLocked(
            java.util.concurrent.Callable<F> firstInvocation,
            java.util.concurrent.Callable<S> secondInvocation
    ) throws Exception {
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        AtomicLong holderConnectionId = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<Long> holder = null;
        Future<F> first = null;
        Future<S> second = null;
        try {
            holder = executor.submit(() -> transactions.execute(status -> {
                Long lockedId = jdbcTemplate.queryForObject("""
                        SELECT reservation_hold_id
                          FROM reservation_holds
                         WHERE reservation_hold_id = 40077
                           FOR UPDATE
                        """, Long.class);
                assertThat(lockedId).isEqualTo(40_077L);
                holderConnectionId.set(jdbcTemplate.queryForObject(
                        "SELECT CONNECTION_ID()", Long.class));
                holderReady.countDown();
                awaitLatch(releaseHolder, "hold row lock release");
                return lockedId;
            }));
            assertThat(holderReady.await(5, TimeUnit.SECONDS)).isTrue();
            first = executor.submit(() -> invokeAfterStart(
                    firstInvocation, workersReady, startWorkers));
            second = executor.submit(() -> invokeAfterStart(
                    secondInvocation, workersReady, startWorkers));
            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            awaitBlockingHoldWaits(holderConnectionId.get(), 2);
            releaseHolder.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return new RacePair<>(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
        } finally {
            holderReady.countDown();
            releaseHolder.countDown();
            workersReady.countDown();
            workersReady.countDown();
            startWorkers.countDown();
            cancelIfRunning(holder);
            cancelIfRunning(first);
            cancelIfRunning(second);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static <T> T invokeAfterStart(
            java.util.concurrent.Callable<T> invocation,
            CountDownLatch workersReady,
            CountDownLatch startWorkers
    ) throws Exception {
        workersReady.countDown();
        awaitLatch(startWorkers, "deposit race worker start");
        return invocation.call();
    }

    private void awaitBlockingHoldWaits(long holderConnectionId, int expectedWaits) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int lastObservedCount = 0;
        try (Connection monitoringConnection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            monitoringConnection.setReadOnly(true);
            try (PreparedStatement statement = monitoringConnection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits AS wait_edge
                    JOIN performance_schema.data_locks AS blocking_lock
                      ON blocking_lock.ENGINE = wait_edge.ENGINE
                     AND blocking_lock.ENGINE_LOCK_ID = wait_edge.BLOCKING_ENGINE_LOCK_ID
                    JOIN information_schema.INNODB_TRX AS blocking_transaction
                      ON blocking_transaction.TRX_ID = blocking_lock.ENGINE_TRANSACTION_ID
                    WHERE blocking_transaction.TRX_MYSQL_THREAD_ID = ?
                      AND blocking_lock.OBJECT_SCHEMA = DATABASE()
                      AND blocking_lock.OBJECT_NAME = 'reservation_holds'
                      AND blocking_lock.INDEX_NAME = 'PRIMARY'
                    """)) {
                statement.setLong(1, holderConnectionId);
                while (System.nanoTime() < deadlineNanos) {
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new AssertionError("lock wait count query returned no row");
                        }
                        lastObservedCount = resultSet.getInt(1);
                    }
                    if (lastObservedCount >= expectedWaits) {
                        return;
                    }
                    Thread.onSpinWait();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to observe MySQL lock waits", exception);
        }
        throw new AssertionError(
                "expected " + expectedWaits + " waits on reservation_holds.PRIMARY"
                        + " but observed " + lastObservedCount);
    }

    private static void awaitLatch(CountDownLatch latch, String name) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(name + " timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " interrupted", exception);
        }
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private record RacePair<F, S>(F first, S second) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock reservationDepositProcessRuntimeClock() {
            return new MutableClock(REQUESTED_AT);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            current.set(value);
        }

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return ZoneOffset.UTC.equals(zone) ? this : Clock.fixed(current.get(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
