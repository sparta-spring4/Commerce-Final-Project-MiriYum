package com.miriyum.domain.payment.service;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.ALREADY_REGISTERED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.REGISTERED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.InspectManualRecoveryQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.AcknowledgeManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ClaimManualRecoveryHandoffsCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistration;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RegisterManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RequestManualRecoveryRefundCommand;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.entity.PaymentRefund;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.repository.PaymentRecoveryHandoffRepository;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import com.miriyum.domain.payment.repository.PaymentRepository;
import com.miriyum.global.exception.ServiceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
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
            "miriyum.payment.cursor-secret=test-history-cursor-secret-with-enough-entropy",
            "miriyum.payment.portone.api-secret=test-api-secret",
            "miriyum.payment.portone.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
            "miriyum.payment.portone.store-id=store-1"
        })
class PaymentRecoveryPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");
    private static final String PAYMENT_ID = "900000000000000001";
    private static final String PROVIDER_ID = "payment-recovery-provider-1";
    private static final String SOURCE_EVENT = "reservation:1:cancelled";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @Autowired PaymentRecoveryHandoffRepository handoffs;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoBean PaymentProviderClient providerClient;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM reservation_payment_recovery_outbox");
        jdbcTemplate.execute("DELETE FROM payment_recovery_handoffs");
        jdbcTemplate.execute("DELETE FROM payment_ledger_entries");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_dispositions");
        jdbcTemplate.execute("DELETE FROM payment_refunds");
        jdbcTemplate.execute("DELETE FROM payment_attempts");
        jdbcTemplate.execute("DELETE FROM payments");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status, created_at, updated_at
                ) VALUES (11, 'recovery@example.com', 'hash', '복구',
                          'ACTIVE', NOW(6), NOW(6))
                """);
        persistFailedRefund();
    }

    @Test
    @DisplayName("실제 MySQL 동시 handoff 등록은 하나의 사건으로 수렴한다")
    void convergesConcurrentRegistration() throws Exception {
        RegisterManualRecoveryHandoffCommand command = registrationCommand();
        CountDownLatch started = new CountDownLatch(2);
        try (Connection blocker = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             PreparedStatement lock = blocker.prepareStatement(
                     "SELECT payment_pk FROM payments WHERE payment_id = ? FOR UPDATE");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            lock.setString(1, PAYMENT_ID);
            lock.executeQuery();
            Future<ManualRecoveryRegistration> first = executor.submit(() -> {
                started.countDown();
                return paymentService.registerManualRecoveryHandoff(command);
            });
            Future<ManualRecoveryRegistration> second = executor.submit(() -> {
                started.countDown();
                return paymentService.registerManualRecoveryHandoff(command);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            blocker.commit();

            var results = List.of(first.get(), second.get());
            assertThat(results).extracting("status")
                    .containsExactlyInAnyOrder(REGISTERED, ALREADY_REGISTERED);
            assertThat(results).extracting("handoffId")
                    .containsOnly(results.getFirst().handoffId());
            assertThat(handoffs.count()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("실제 MySQL에서 결과 불명 operation replay는 provider 취소를 한 번만 호출한다")
    void doesNotResendUnknownOperation() {
        String handoffId = paymentService.registerManualRecoveryHandoff(registrationCommand())
                .handoffId();
        var snapshot = paymentService.inspectManualRecovery(
                new InspectManualRecoveryQuery(handoffId));
        RequestManualRecoveryRefundCommand command = new RequestManualRecoveryRefundCommand(
                handoffId, snapshot.handoffVersion(), snapshot.paymentVersion(),
                snapshot.recoveryVersion(), "550e8400-e29b-41d4-a716-446655440099");
        when(providerClient.cancelPayment(
                eq(PROVIDER_ID), anyString(), eq(100_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED")))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));

        var first = paymentService.requestManualRecoveryRefund(command);
        var replay = paymentService.requestManualRecoveryRefund(command);

        assertThat(first.status()).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
        assertThat(replay).isEqualTo(first);
        verify(providerClient, times(1)).cancelPayment(
                eq(PROVIDER_ID), anyString(), eq(100_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_RETRY_REQUESTED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("실제 MySQL lease 재선점은 이전 claim token의 acknowledgement를 거부한다")
    void rejectsExpiredClaimTokenAfterReclaim() {
        String handoffId = paymentService.registerManualRecoveryHandoff(registrationCommand())
                .handoffId();
        var first = paymentService.claimManualRecoveryHandoffs(
                new ClaimManualRecoveryHandoffsCommand("intake-a", 1)).getFirst();
        jdbcTemplate.update("""
                UPDATE payment_recovery_handoffs
                   SET lease_until = DATE_SUB(NOW(6), INTERVAL 1 SECOND)
                 WHERE payment_recovery_handoff_id = ?
                """, Long.parseLong(handoffId));
        var second = paymentService.claimManualRecoveryHandoffs(
                new ClaimManualRecoveryHandoffsCommand("intake-b", 1)).getFirst();

        assertThat(second.claimToken()).isGreaterThan(first.claimToken());
        assertThatThrownBy(() -> paymentService.acknowledgeManualRecoveryHandoff(
                new AcknowledgeManualRecoveryHandoffCommand(
                        handoffId, "intake-a", first.claimToken(), "case-1")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_RECOVERY_STALE);

        paymentService.acknowledgeManualRecoveryHandoff(
                new AcknowledgeManualRecoveryHandoffCommand(
                        handoffId, "intake-b", second.claimToken(), "case-1"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM payment_recovery_handoffs
                 WHERE payment_recovery_handoff_id = ?
                """, String.class, Long.parseLong(handoffId))).isEqualTo("ACKNOWLEDGED");
    }

    private void persistFailedRefund() {
        transactionTemplate.executeWithoutResult(ignored -> {
            Payment payment = Payment.prepare(
                    PAYMENT_ID, "RESERVATION_DEPOSIT", "1", 1L,
                    NOW.plusSeconds(3600),
                    "550e8400-e29b-41d4-a716-446655440010",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    11L, 300_000L, "KRW", PROVIDER_ID, "예약금", NOW.minusSeconds(100));
            payment.beginConfirmation(NOW.minusSeconds(90));
            payment.markPaid("provider-transaction-1", NOW.minusSeconds(80));
            payments.saveAndFlush(payment);
            PaymentRefund refund = PaymentRefund.request(
                    "910000000000000001", payment, KEY,
                    refundFingerprint(),
                    SOURCE_EVENT, 100_000L, "RESERVATION_CANCELLED", 1L,
                    NOW.minusSeconds(20));
            refund.fail(NOW.minusSeconds(10));
            refunds.saveAndFlush(refund);
        });
    }

    private static RegisterManualRecoveryHandoffCommand registrationCommand() {
        return new RegisterManualRecoveryHandoffCommand(
                RESERVATION_DEPOSIT_REFUND, "31", PAYMENT_ID, SOURCE_EVENT, KEY);
    }

    private static String refundFingerprint() {
        String canonical = PAYMENT_ID + "\n" + SOURCE_EVENT + "\n100000\n"
                + "RESERVATION_CANCELLED\n1";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
