package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.entity.ReservationDepositProcess;
import com.miriyum.domain.reservation.entity.ReservationDepositRefundObligation;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositRefundObligationRepository;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.Calculation;
import com.miriyum.domain.reservation.service.ReservationDepositCalculator.ItemSnapshot;
import com.miriyum.domain.schedule.service.StoreScheduleActivationJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-d")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.task.scheduling.enabled=false",
            "miriyum.reservation.hold-expiration.enabled=false",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes"
        })
@Import(ReservationDepositRefundRuntimeIT.MutableClockConfig.class)
class ReservationDepositRefundRuntimeIT {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final String PAYMENT_ID = "900000000000000077";
    private static final String IDEMPOTENCY_KEY =
            "550e8400-e29b-41d4-a716-446655440077";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationDepositProcessRepository processRepository;

    @Autowired
    private ReservationDepositRefundObligationRepository refundRepository;

    @Autowired
    private ReservationDepositRefundService refundService;

    @Autowired
    private ReservationDepositRefundJob refundJob;

    @Autowired
    private MutableClock clock;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private StoreScheduleActivationJob storeScheduleActivationJob;

    @BeforeEach
    void resetDatabase() {
        clock.set(NOW);
        jdbcTemplate.execute("DELETE FROM reservation_deposit_refund_obligations");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_cause_audits");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_calculation_items");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_processes");
        jdbcTemplate.execute("DELETE FROM reservation_holds");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM store_operator_accounts");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
    }

    @Test
    void commitsClaimBeforeCallingPaymentOutsideReservationTransaction() {
        Fixture fixture = createRefundRequiredFixture();
        AtomicReference<Boolean> paymentTransactionActive = new AtomicReference<>();
        AtomicReference<String> obligationStatusAtPayment = new AtomicReference<>();
        AtomicReference<String> processStatusAtPayment = new AtomicReference<>();
        AtomicReference<RequestRefundCommand> paymentCommand = new AtomicReference<>();
        when(paymentService.requestRefund(any(RequestRefundCommand.class)))
                .thenAnswer(invocation -> {
                    paymentTransactionActive.set(TransactionSynchronizationManager
                            .isActualTransactionActive());
                    obligationStatusAtPayment.set(statusOf(
                            "reservation_deposit_refund_obligations",
                            "reservation_deposit_refund_obligation_id",
                            fixture.obligationId()));
                    processStatusAtPayment.set(statusOf(
                            "reservation_deposit_processes",
                            "reservation_deposit_process_id",
                            fixture.processId()));
                    paymentCommand.set(invocation.getArgument(0));
                    return completedRefund();
                });
        assertThat(statusOf("reservation_deposit_refund_obligations",
                "reservation_deposit_refund_obligation_id",
                fixture.obligationId())).isEqualTo("REQUIRED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT next_attempt_at <= ?
                FROM reservation_deposit_refund_obligations
                WHERE reservation_deposit_refund_obligation_id = ?
                """, Boolean.class, clock.instant(), fixture.obligationId())).isTrue();

        int completed = refundJob.runOnce("refund-worker-a", 10);

        assertThat(statusOf("reservation_deposit_refund_obligations",
                "reservation_deposit_refund_obligation_id",
                fixture.obligationId())).isEqualTo("COMPLETED");
        assertThat(paymentTransactionActive).hasValue(false);
        assertThat(obligationStatusAtPayment).hasValue("PROCESSING");
        assertThat(processStatusAtPayment).hasValue("COMPENSATING");
        assertThat(paymentCommand).hasValue(new RequestRefundCommand(
                PAYMENT_ID,
                "reservation-deposit-compensation:" + fixture.processId(),
                4_000L,
                "FULL_DEPOSIT_COMPENSATION",
                1L,
                IDEMPOTENCY_KEY));
        assertThat(statusOf("reservation_deposit_processes",
                "reservation_deposit_process_id",
                fixture.processId())).isEqualTo("COMPENSATED");
        assertThat(completed).isEqualTo(1);
    }

    @Test
    void exactLeaseExpiryReclaimsAndFencesTheStaleWorkerResult() {
        Fixture fixture = createRefundRequiredFixture();

        ReservationDepositRefundService.Claim stale =
                refundService.claimDue("refund-worker-a", 1).getFirst();
        clock.advance(Duration.ofSeconds(30));
        ReservationDepositRefundService.Claim current =
                refundService.claimDue("refund-worker-b", 1).getFirst();

        assertThat(stale.obligationId()).isEqualTo(fixture.obligationId());
        assertThat(current.obligationId()).isEqualTo(fixture.obligationId());
        assertThat(current.token()).isGreaterThan(stale.token());
        assertThat(refundService.recordCompleted(stale, completedRefund())).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lease_owner
                FROM reservation_deposit_refund_obligations
                WHERE reservation_deposit_refund_obligation_id = ?
                """, String.class, fixture.obligationId())).isEqualTo("refund-worker-b");
        assertThat(statusOf("reservation_deposit_processes",
                "reservation_deposit_process_id",
                fixture.processId())).isEqualTo("COMPENSATING");

        assertThat(refundService.recordCompleted(current, completedRefund())).isTrue();
        assertThat(statusOf("reservation_deposit_refund_obligations",
                "reservation_deposit_refund_obligation_id",
                fixture.obligationId())).isEqualTo("COMPLETED");
        assertThat(statusOf("reservation_deposit_processes",
                "reservation_deposit_process_id",
                fixture.processId())).isEqualTo("COMPENSATED");
    }

    @Test
    void retryablePaymentFailureRequeuesThenUsesTheSameRefundIdentity() {
        Fixture fixture = createRefundRequiredFixture();
        when(paymentService.requestRefund(any(RequestRefundCommand.class)))
                .thenThrow(new IllegalStateException("temporary payment failure"))
                .thenReturn(completedRefund());

        assertThat(refundJob.runOnce("refund-worker-a", 10)).isZero();

        assertThat(statusOf("reservation_deposit_refund_obligations",
                "reservation_deposit_refund_obligation_id",
                fixture.obligationId())).isEqualTo("REQUIRED");
        assertThat(statusOf("reservation_deposit_processes",
                "reservation_deposit_process_id",
                fixture.processId())).isEqualTo("COMPENSATING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT next_attempt_at IS NOT NULL
                FROM reservation_deposit_refund_obligations
                WHERE reservation_deposit_refund_obligation_id = ?
                """, Boolean.class, fixture.obligationId())).isTrue();

        clock.advance(Duration.ofSeconds(30));
        assertThat(refundJob.runOnce("refund-worker-b", 10)).isEqualTo(1);

        ArgumentCaptor<RequestRefundCommand> commands =
                ArgumentCaptor.forClass(RequestRefundCommand.class);
        verify(paymentService, times(2)).requestRefund(commands.capture());
        assertThat(commands.getAllValues())
                .containsExactly(commands.getAllValues().getFirst(),
                        commands.getAllValues().getFirst());
        assertThat(commands.getValue().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(statusOf("reservation_deposit_refund_obligations",
                "reservation_deposit_refund_obligation_id",
                fixture.obligationId())).isEqualTo("COMPLETED");
        assertThat(statusOf("reservation_deposit_processes",
                "reservation_deposit_process_id",
                fixture.processId())).isEqualTo("COMPENSATED");
    }

    private Fixture createRefundRequiredFixture() {
        seedHoldOwnerAndStore();
        Instant expiresAt = NOW.plusSeconds(600);
        ReservationDepositProcess process = ReservationDepositProcess.awaitingPayment(
                40_077L,
                10_077L,
                expiresAt,
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
                        PAYMENT_ID,
                        "deposit-refund-portone",
                        "미리윰 식당 예약금",
                        4_000L,
                        "KRW",
                        expiresAt,
                        PaymentStatus.READY),
                NOW.minusSeconds(60));
        process.requireCompensation(NOW.minusSeconds(1));
        ReservationDepositProcess savedProcess = processRepository.saveAndFlush(process);
        ReservationDepositRefundObligation savedObligation = refundRepository.saveAndFlush(
                ReservationDepositRefundObligation.required(
                        savedProcess.getId(),
                        PAYMENT_ID,
                        4_000L,
                        "KRW",
                        1L,
                        "reservation-deposit-compensation:" + savedProcess.getId(),
                        IDEMPOTENCY_KEY,
                        "FULL_DEPOSIT_COMPENSATION",
                        NOW));
        return new Fixture(savedProcess.getId(), savedObligation.getId());
    }

    private RefundResult completedRefund() {
        return new RefundResult(
                "refund-7001",
                PAYMENT_ID,
                4_000L,
                4_000L,
                4_000L,
                0L,
                "KRW",
                RefundStatus.COMPLETED,
                NOW,
                clock.instant());
    }

    private String statusOf(String table, String idColumn, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + table + " WHERE " + idColumn + " = ?",
                String.class,
                id);
    }

    private void seedHoldOwnerAndStore() {
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name,
                    status, created_at, updated_at
                ) VALUES (
                    10077, 'deposit-refund@example.com', 'hash', '예약금 회원',
                    'ACTIVE', NOW(6), NOW(6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts (
                    store_operator_account_id, email, password_hash, display_name,
                    status, created_at, updated_at
                ) VALUES (
                    20077, 'deposit-refund-owner@example.com', 'hash', '예약금 운영자',
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
                    30077, 20077, '9876543277', 'CAFE', '예약금 환불 매장', '',
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
                    40077, 10077, 30077, '예약금 환불 매장', '2026-08-21',
                    '2026-08-21 09:00:00.000000', '2026-08-21 10:00:00.000000',
                    '2026-08-21 10:10:00.000000', 'Asia/Seoul', 32400, 32400,
                    32400, 10, 60, 10, 30077, 9, 2, 0, 0,
                    'consumer:10077:channel:primary', TRUE, 7, 8, 'ACTIVE', 0,
                    'deposit-refund-hold-77', '2026-08-20 09:00:00.000000',
                    '2026-08-20 09:10:00.000000'
                )
                """);
    }

    private record Fixture(long processId, long obligationId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock reservationDepositRefundRuntimeClock() {
            return new MutableClock(NOW);
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
