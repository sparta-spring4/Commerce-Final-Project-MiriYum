package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentContracts.ApplyReservationDepositDispositionCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionFailureClassification;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.GetReservationDepositDispositionQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.VerifiedWaitingReservationDeposit;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        }
)
class PaymentPersistenceIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private PaymentService paymentService;

    @MockitoSpyBean
    private PaymentTransactionService transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentWebhookService webhookService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private PaymentProviderClient providerClient;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM waiting_conversion_compensations");
        jdbcTemplate.execute("DELETE FROM payment_webhook_receipts");
        jdbcTemplate.execute("DELETE FROM payment_ledger_entries");
        jdbcTemplate.execute("DELETE FROM reservation_deposit_dispositions");
        jdbcTemplate.execute("DELETE FROM payment_refunds");
        jdbcTemplate.execute("DELETE FROM payment_attempts");
        jdbcTemplate.execute("DELETE FROM payments");
        jdbcTemplate.execute("DELETE FROM consumer_accounts");
        jdbcTemplate.update("""
                INSERT INTO consumer_accounts (
                    consumer_account_id, email, password_hash, name, status, created_at, updated_at
                ) VALUES (11, 'payment-owner@example.com', 'hash', '결제소유자',
                          'ACTIVE', NOW(6), NOW(6))
                """);
    }

    @Test
    @DisplayName("예약금 50퍼센트 처분과 재생은 하나의 외부 환불·처분 원장으로 수렴한다")
    void convergesReservationDepositDispositionReplay() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("201");
        ApplyReservationDepositDispositionCommand command = dispositionCommand(
                preparation.paymentId(),
                "reservation:201:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440201");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(new ProviderCancellation(
                        "cancellation-disposition-201",
                        ProviderStatus.PARTIALLY_CANCELLED,
                        15_000L,
                        "KRW"));

        DispositionResult first = paymentService.applyReservationDepositDisposition(command);
        DispositionResult replay = paymentService.applyReservationDepositDisposition(command);
        ApplyReservationDepositDispositionCommand sameSourceWithNewKey = dispositionCommand(
                preparation.paymentId(),
                command.sourceEventId(),
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440207");
        DispositionResult sourceReplay = paymentService.applyReservationDepositDisposition(
                sameSourceWithNewKey);
        DispositionResult queried = paymentService.getReservationDepositDisposition(
                new GetReservationDepositDispositionQuery(
                        preparation.paymentId(), command.sourceEventId()));

        assertThat(first.status()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(first.completedRefundAmountMinor()).isEqualTo(15_000L);
        assertThat(replay).isEqualTo(first);
        assertThat(sourceReplay).isEqualTo(first);
        assertThat(queried).isEqualTo(first);
        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), eq(first.refundId()), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_deposit_dispositions", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("provider 명시 실패 재시도는 같은 처분·환불 ID로만 완료한다")
    void retriesExplicitDispositionFailureWithSameRefund() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("202");
        ApplyReservationDepositDispositionCommand command = dispositionCommand(
                preparation.paymentId(),
                "reservation:202:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440202");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(
                        new ProviderCancellation(
                                "cancellation-disposition-failed-202",
                                ProviderStatus.FAILED,
                                15_000L,
                                "KRW"),
                        new ProviderCancellation(
                                "cancellation-disposition-completed-202",
                                ProviderStatus.PARTIALLY_CANCELLED,
                                15_000L,
                                "KRW"));

        DispositionResult failed = paymentService.applyReservationDepositDisposition(command);
        DispositionResult completed = paymentService.applyReservationDepositDisposition(command);

        assertThat(failed.status()).isEqualTo(DispositionStatus.FAILED);
        assertThat(failed.failureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);
        assertThat(completed.status()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(completed.dispositionId()).isEqualTo(failed.dispositionId());
        assertThat(completed.refundId()).isEqualTo(failed.refundId());
        verify(providerClient, times(2)).cancelPayment(
                eq(preparation.portOnePaymentId()), eq(failed.refundId()), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM payment_refunds", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM reservation_deposit_dispositions", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_RETRY_REQUESTED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("완료된 50퍼센트 처분을 100퍼센트로 정정하면 차액만 추가 환불한다")
    void refundsOnlyIncrementalAmountForDispositionCorrection() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("203");
        ApplyReservationDepositDispositionCommand first = dispositionCommand(
                preparation.paymentId(),
                "reservation:203:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440203");
        ApplyReservationDepositDispositionCommand correction = dispositionCommand(
                preparation.paymentId(),
                "reservation:203:correction:1",
                first.sourceEventId(),
                10000,
                "550e8400-e29b-41d4-a716-446655440204");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(
                        new ProviderCancellation(
                                "cancellation-disposition-203-a",
                                ProviderStatus.PARTIALLY_CANCELLED,
                                15_000L,
                                "KRW"),
                        new ProviderCancellation(
                                "cancellation-disposition-203-b",
                                ProviderStatus.CANCELLED,
                                15_000L,
                                "KRW"));

        DispositionResult firstResult =
                paymentService.applyReservationDepositDisposition(first);
        DispositionResult corrected =
                paymentService.applyReservationDepositDisposition(correction);

        assertThat(firstResult.completedRefundAmountMinor()).isEqualTo(15_000L);
        assertThat(corrected.targetRefundAmountMinor()).isEqualTo(30_000L);
        assertThat(corrected.incrementalRefundAmountMinor()).isEqualTo(15_000L);
        assertThat(corrected.completedRefundAmountMinor()).isEqualTo(30_000L);
        assertThat(corrected.withheldAmountMinor()).isZero();
        verify(providerClient, times(2)).cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
    }

    @Test
    @DisplayName("같은 완료 처분을 다시 정정하면 외부 호출 없이 영구 실패해 sibling 초과 환불을 막는다")
    void rejectsSequentialSiblingDispositionCorrection() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("208");
        ApplyReservationDepositDispositionCommand parent = dispositionCommand(
                preparation.paymentId(),
                "reservation:208:cancelled",
                null,
                0,
                "550e8400-e29b-41d4-a716-446655440220");
        ApplyReservationDepositDispositionCommand firstCorrection = dispositionCommand(
                preparation.paymentId(),
                "reservation:208:correction:1",
                parent.sourceEventId(),
                5000,
                "550e8400-e29b-41d4-a716-446655440221");
        ApplyReservationDepositDispositionCommand siblingCorrection = dispositionCommand(
                preparation.paymentId(),
                "reservation:208:correction:2",
                parent.sourceEventId(),
                5000,
                "550e8400-e29b-41d4-a716-446655440222");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(
                        new ProviderCancellation(
                                "cancellation-disposition-208-a",
                                ProviderStatus.PARTIALLY_CANCELLED,
                                15_000L,
                                "KRW"),
                        new ProviderCancellation(
                                "cancellation-disposition-208-b",
                                ProviderStatus.CANCELLED,
                                15_000L,
                                "KRW"));

        paymentService.applyReservationDepositDisposition(parent);
        DispositionResult first =
                paymentService.applyReservationDepositDisposition(firstCorrection);
        DispositionResult sibling =
                paymentService.applyReservationDepositDisposition(siblingCorrection);

        assertThat(first.status()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(sibling.status()).isEqualTo(DispositionStatus.FAILED);
        assertThat(sibling.failureClassification())
                .isEqualTo(DispositionFailureClassification.PERMANENT);
        assertThat(sibling.incrementalRefundAmountMinor()).isEqualTo(15_000L);
        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("동시 sibling 정정도 부모당 한 건만 provider를 호출한다")
    void rejectsConcurrentSiblingDispositionCorrection() throws Exception {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("209");
        ApplyReservationDepositDispositionCommand parent = dispositionCommand(
                preparation.paymentId(),
                "reservation:209:cancelled",
                null,
                0,
                "550e8400-e29b-41d4-a716-446655440223");
        paymentService.applyReservationDepositDisposition(parent);
        ApplyReservationDepositDispositionCommand firstCorrection = dispositionCommand(
                preparation.paymentId(),
                "reservation:209:correction:1",
                parent.sourceEventId(),
                5000,
                "550e8400-e29b-41d4-a716-446655440224");
        ApplyReservationDepositDispositionCommand siblingCorrection = dispositionCommand(
                preparation.paymentId(),
                "reservation:209:correction:2",
                parent.sourceEventId(),
                5000,
                "550e8400-e29b-41d4-a716-446655440225");
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenAnswer(invocation -> {
                    int call = providerCalls.incrementAndGet();
                    if (call == 1) {
                        providerEntered.countDown();
                        awaitLatch(releaseProvider, "disposition provider release");
                    }
                    return new ProviderCancellation(
                            "cancellation-disposition-209-" + call,
                            ProviderStatus.PARTIALLY_CANCELLED,
                            15_000L,
                            "KRW");
                });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<DispositionResult> first = executor.submit(() ->
                    paymentService.applyReservationDepositDisposition(firstCorrection));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<DispositionResult> sibling = executor.submit(() ->
                    paymentService.applyReservationDepositDisposition(siblingCorrection));
            DispositionResult siblingResult = sibling.get(5, TimeUnit.SECONDS);
            releaseProvider.countDown();
            DispositionResult firstResult = first.get(5, TimeUnit.SECONDS);

            assertThat(List.of(firstResult.status(), siblingResult.status()))
                    .containsExactlyInAnyOrder(
                            DispositionStatus.COMPLETED,
                            DispositionStatus.FAILED);
        } finally {
            releaseProvider.countDown();
        }
        assertThat(providerCalls).hasValue(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("완료액보다 낮은 목표 정정은 외부 호출 없이 실제 완료액을 보존해 영구 실패한다")
    void rejectsDispositionTargetDecreasePermanently() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("204");
        ApplyReservationDepositDispositionCommand first = dispositionCommand(
                preparation.paymentId(),
                "reservation:204:cancelled",
                null,
                10000,
                "550e8400-e29b-41d4-a716-446655440205");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(30_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(new ProviderCancellation(
                        "cancellation-disposition-204",
                        ProviderStatus.CANCELLED,
                        30_000L,
                        "KRW"));
        paymentService.applyReservationDepositDisposition(first);
        ApplyReservationDepositDispositionCommand correction = dispositionCommand(
                preparation.paymentId(),
                "reservation:204:correction:1",
                first.sourceEventId(),
                5000,
                "550e8400-e29b-41d4-a716-446655440206");

        DispositionResult rejected =
                paymentService.applyReservationDepositDisposition(correction);

        assertThat(rejected.status()).isEqualTo(DispositionStatus.FAILED);
        assertThat(rejected.failureClassification())
                .isEqualTo(DispositionFailureClassification.PERMANENT);
        assertThat(rejected.targetRefundAmountMinor()).isEqualTo(15_000L);
        assertThat(rejected.completedRefundAmountMinor()).isEqualTo(30_000L);
        assertThat(rejected.incrementalRefundAmountMinor()).isZero();
        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(30_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
    }

    @Test
    @DisplayName("예약금 처분의 provider mapping 불일치는 새 환불 없이 UNKNOWN 대사 상태로 재생한다")
    void isolatesDispositionProviderMismatchForReconciliation() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("205");
        ApplyReservationDepositDispositionCommand command = dispositionCommand(
                preparation.paymentId(),
                "reservation:205:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440208");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(new ProviderCancellation(
                        "cancellation-disposition-mismatch-205",
                        ProviderStatus.PARTIALLY_CANCELLED,
                        14_999L,
                        "KRW"));

        DispositionResult first = paymentService.applyReservationDepositDisposition(command);
        DispositionResult replay = paymentService.applyReservationDepositDisposition(command);

        assertThat(first.status()).isEqualTo(DispositionStatus.RECONCILIATION_REQUIRED);
        assertThat(first.failureClassification())
                .isEqualTo(DispositionFailureClassification.UNKNOWN);
        assertThat(first.refundId()).isNotNull();
        assertThat(replay).isEqualTo(first);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), eq(first.refundId()), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("응답 유실된 예약금 취소는 marker가 일치한 provider GET으로 한 번만 대사 완료한다")
    void reconcilesUnknownDispositionFromProviderCancellationOnce() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("212");
        ApplyReservationDepositDispositionCommand command = dispositionCommand(
                preparation.paymentId(),
                "reservation:212:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440212");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));

        DispositionResult unknown = paymentService.applyReservationDepositDisposition(command);
        ProviderCancellation reconciledCancellation = new ProviderCancellation(
                "cancellation-disposition-212",
                ProviderStatus.PARTIALLY_CANCELLED,
                15_000L,
                "KRW",
                PaymentProviderClient.cancellationReason(
                        "RESERVATION_DEPOSIT_DISPOSITION", unknown.refundId()));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ProviderPayment(
                    preparation.portOnePaymentId(),
                    "transaction-212",
                    ProviderStatus.PARTIALLY_CANCELLED,
                    30_000L,
                    "KRW",
                    List.of(reconciledCancellation));
        });
        GetReservationDepositDispositionQuery query =
                new GetReservationDepositDispositionQuery(
                        preparation.paymentId(), command.sourceEventId());

        DispositionResult completed = paymentService.getReservationDepositDisposition(query);
        DispositionResult replay = paymentService.getReservationDepositDisposition(query);

        assertThat(unknown.status()).isEqualTo(DispositionStatus.RECONCILIATION_REQUIRED);
        assertThat(completed.status()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(completed.completedRefundAmountMinor()).isEqualTo(15_000L);
        assertThat(replay).isEqualTo(completed);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_refunds
                 WHERE refund_id = ?
                   AND status = 'COMPLETED'
                   AND provider_cancellation_id = ?
                """, Long.class, unknown.refundId(), "cancellation-disposition-212"))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE event_key = ? AND entry_type = 'REFUND_COMPLETED'
                """, Long.class, "refund-completed:cancellation-disposition-212"))
                .isEqualTo(1L);
        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), eq(unknown.refundId()), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
        verify(providerClient, times(2)).getPayment(preparation.portOnePaymentId());
    }

    @Test
    @DisplayName("provider GET 중 refund 상태가 바뀌면 stale 취소 성공을 원장에 적용하지 않는다")
    void ignoresStaleProviderCancellationAfterRefundStateChanges() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("213");
        ApplyReservationDepositDispositionCommand command = dispositionCommand(
                preparation.paymentId(),
                "reservation:213:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440213");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        DispositionResult unknown = paymentService.applyReservationDepositDisposition(command);
        ProviderCancellation staleCancellation = new ProviderCancellation(
                "cancellation-stale-213",
                ProviderStatus.PARTIALLY_CANCELLED,
                15_000L,
                "KRW",
                PaymentProviderClient.cancellationReason(
                        "RESERVATION_DEPOSIT_DISPOSITION", unknown.refundId()));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenAnswer(invocation -> {
            jdbcTemplate.update("""
                    UPDATE payment_refunds
                       SET status = 'FAILED', updated_at = NOW(6)
                     WHERE refund_id = ?
                    """, unknown.refundId());
            return new ProviderPayment(
                    preparation.portOnePaymentId(),
                    "transaction-213",
                    ProviderStatus.PARTIALLY_CANCELLED,
                    30_000L,
                    "KRW",
                    List.of(staleCancellation));
        });

        DispositionResult result = paymentService.getReservationDepositDisposition(
                new GetReservationDepositDispositionQuery(
                        preparation.paymentId(), command.sourceEventId()));

        assertThat(result.status()).isEqualTo(DispositionStatus.RECONCILIATION_REQUIRED);
        assertThat(result.completedRefundAmountMinor()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM payment_refunds WHERE refund_id = ?
                """, String.class, unknown.refundId())).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE event_key = 'refund-completed:cancellation-stale-213'
                """, Long.class)).isZero();
    }

    @Test
    @DisplayName("marker가 일치한 provider 취소 명시 실패는 같은 환불을 retryable 실패로 종결한다")
    void reconcilesUnknownDispositionToExplicitFailure() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("214");
        ApplyReservationDepositDispositionCommand command = dispositionCommand(
                preparation.paymentId(),
                "reservation:214:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440214");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        DispositionResult unknown = paymentService.applyReservationDepositDisposition(command);
        ProviderCancellation failedCancellation = new ProviderCancellation(
                "cancellation-failed-214",
                ProviderStatus.FAILED,
                15_000L,
                "KRW",
                PaymentProviderClient.cancellationReason(
                        "RESERVATION_DEPOSIT_DISPOSITION", unknown.refundId()));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-214",
                        ProviderStatus.PAID,
                        30_000L,
                        "KRW",
                        List.of(failedCancellation)));
        GetReservationDepositDispositionQuery query =
                new GetReservationDepositDispositionQuery(
                        preparation.paymentId(), command.sourceEventId());

        DispositionResult failed = paymentService.getReservationDepositDisposition(query);
        DispositionResult replay = paymentService.getReservationDepositDisposition(query);

        assertThat(failed.status()).isEqualTo(DispositionStatus.FAILED);
        assertThat(failed.failureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);
        assertThat(failed.completedRefundAmountMinor()).isZero();
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(replay).isEqualTo(failed);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM payment_refunds WHERE refund_id = ?
                """, String.class, unknown.refundId())).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE event_key = ? AND entry_type = 'REFUND_FAILED'
                """, Long.class,
                "refund-failed:" + unknown.refundId() + ":1")).isEqualTo(1L);
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), eq(unknown.refundId()), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(new ProviderCancellation(
                        "cancellation-retried-214",
                        ProviderStatus.PARTIALLY_CANCELLED,
                        15_000L,
                        "KRW"));

        DispositionResult retried =
                paymentService.applyReservationDepositDisposition(command);

        assertThat(retried.status()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(retried.refundId()).isEqualTo(unknown.refundId());
        verify(providerClient, times(2)).cancelPayment(
                eq(preparation.portOnePaymentId()), eq(unknown.refundId()), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
        verify(providerClient, times(2)).getPayment(preparation.portOnePaymentId());
    }

    @Test
    @DisplayName("미완료 처분을 참조한 정정은 외부 호출 없이 영구 실패로 기록한다")
    void rejectsCorrectionOfUnfinishedDispositionPermanently() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("206");
        ApplyReservationDepositDispositionCommand unfinished = dispositionCommand(
                preparation.paymentId(),
                "reservation:206:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440209");
        PaymentTransactionService.DispositionClaim claim = transactions.claimDisposition(
                unfinished, Instant.now().truncatedTo(ChronoUnit.MICROS));
        assertThat(claim.requiresRefund()).isTrue();
        ApplyReservationDepositDispositionCommand correction = dispositionCommand(
                preparation.paymentId(),
                "reservation:206:correction:1",
                unfinished.sourceEventId(),
                10000,
                "550e8400-e29b-41d4-a716-446655440210");

        DispositionResult rejected =
                paymentService.applyReservationDepositDisposition(correction);

        assertThat(rejected.status()).isEqualTo(DispositionStatus.FAILED);
        assertThat(rejected.failureClassification())
                .isEqualTo(DispositionFailureClassification.PERMANENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_deposit_dispositions", Long.class))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds", Long.class)).isZero();
        verify(providerClient, never()).cancelPayment(
                anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("실패 처분 재시도는 최신 환불 잔액을 다시 검증해 provider 초과 환불을 막는다")
    void revalidatesRefundableAmountBeforeDispositionRetry() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("207");
        ApplyReservationDepositDispositionCommand disposition = dispositionCommand(
                preparation.paymentId(),
                "reservation:207:cancelled",
                null,
                10000,
                "550e8400-e29b-41d4-a716-446655440211");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(30_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(new ProviderCancellation(
                        "cancellation-disposition-failed-207",
                        ProviderStatus.FAILED,
                        30_000L,
                        "KRW"));
        DispositionResult failed =
                paymentService.applyReservationDepositDisposition(disposition);
        assertThat(failed.failureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);

        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(20_000L),
                eq("KRW"), eq("RESERVATION_ADJUSTED")))
                .thenReturn(new ProviderCancellation(
                        "cancellation-sibling-207",
                        ProviderStatus.PARTIALLY_CANCELLED,
                        20_000L,
                        "KRW"));
        paymentService.requestRefund(new RequestRefundCommand(
                preparation.paymentId(),
                "reservation:207:adjusted",
                20_000L,
                "RESERVATION_ADJUSTED",
                7L,
                "550e8400-e29b-41d4-a716-446655440212"));

        DispositionResult rejectedRetry =
                paymentService.applyReservationDepositDisposition(disposition);

        assertThat(rejectedRetry.status()).isEqualTo(DispositionStatus.FAILED);
        assertThat(rejectedRetry.failureClassification())
                .isEqualTo(DispositionFailureClassification.PERMANENT);
        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), eq(failed.refundId()), eq(30_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
    }

    @Test
    @DisplayName("환불 재시도 lease는 최초 요청이 아니라 최신 시도 시작 시각부터 계산한다")
    void startsFreshLeaseForExplicitRefundRetry() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("208");
        RequestRefundCommand command = new RequestRefundCommand(
                preparation.paymentId(),
                "reservation:208:cancelled",
                10_000L,
                "RESERVATION_CANCELLED",
                7L,
                "550e8400-e29b-41d4-a716-446655440213");
        Instant firstClaimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PaymentTransactionService.RefundClaim first =
                transactions.claimRefund(command, firstClaimedAt);
        transactions.finalizeRefund(
                first,
                new ProviderCancellation(
                        "cancellation-failed-208",
                        ProviderStatus.FAILED,
                        10_000L,
                        "KRW"),
                firstClaimedAt.plusSeconds(1));

        Instant retriedAt = firstClaimedAt.plusSeconds(600);
        PaymentTransactionService.RefundClaim retry =
                transactions.claimRefund(command, retriedAt);
        PaymentTransactionService.RefundClaim activeReplay =
                transactions.claimRefund(command, retriedAt.plusSeconds(299));
        PaymentTransactionService.RefundClaim staleReplay =
                transactions.claimRefund(command, retriedAt.plusSeconds(301));

        assertThat(retry.requiresProviderCall()).isTrue();
        assertThat(activeReplay.completedResult().status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(staleReplay.completedResult().status())
                .isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("예약금 처분 provider 호출 동안 ambient caller transaction을 중단한다")
    void suspendsCallerTransactionAroundDispositionProviderCall() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("209");
        ApplyReservationDepositDispositionCommand command = dispositionCommand(
                preparation.paymentId(),
                "reservation:209:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440214");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return new ProviderCancellation(
                            "cancellation-disposition-209",
                            ProviderStatus.PARTIALLY_CANCELLED,
                            15_000L,
                            "KRW");
                });
        AtomicReference<DispositionResult> observed = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            observed.set(paymentService.applyReservationDepositDisposition(command));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });

        assertThat(observed.get().status()).isEqualTo(DispositionStatus.COMPLETED);
    }

    @Test
    @DisplayName("같은 환불의 반복 명시 실패는 attempt별 감사 원장을 남긴 뒤 동일 ID로 완료한다")
    void auditsRepeatedExplicitRefundFailuresByAttempt() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("210");
        ApplyReservationDepositDispositionCommand command = dispositionCommand(
                preparation.paymentId(),
                "reservation:210:cancelled",
                null,
                5000,
                "550e8400-e29b-41d4-a716-446655440215");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(15_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(
                        new ProviderCancellation(
                                "cancellation-failed-210-a",
                                ProviderStatus.FAILED,
                                15_000L,
                                "KRW"),
                        new ProviderCancellation(
                                "cancellation-failed-210-b",
                                ProviderStatus.FAILED,
                                15_000L,
                                "KRW"),
                        new ProviderCancellation(
                                "cancellation-completed-210",
                                ProviderStatus.PARTIALLY_CANCELLED,
                                15_000L,
                                "KRW"));

        DispositionResult first = paymentService.applyReservationDepositDisposition(command);
        DispositionResult second = paymentService.applyReservationDepositDisposition(command);
        DispositionResult completed = paymentService.applyReservationDepositDisposition(command);

        assertThat(first.failureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);
        assertThat(second.failureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);
        assertThat(completed.status()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(completed.refundId()).isEqualTo(first.refundId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_FAILED'
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_RETRY_REQUESTED'
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("처분 retry claim 뒤 경쟁 환불이 잔액을 쓰면 provider 호출 없이 즉시 영구 실패한다")
    void rejectsDispositionRetryRacePermanently() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("211");
        ApplyReservationDepositDispositionCommand disposition = dispositionCommand(
                preparation.paymentId(),
                "reservation:211:cancelled",
                null,
                10000,
                "550e8400-e29b-41d4-a716-446655440216");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(30_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION")))
                .thenReturn(new ProviderCancellation(
                        "cancellation-disposition-failed-211",
                        ProviderStatus.FAILED,
                        30_000L,
                        "KRW"));
        DispositionResult failed =
                paymentService.applyReservationDepositDisposition(disposition);
        assertThat(failed.failureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);

        PaymentTransactionService.DispositionClaim retryClaim = transactions.claimDisposition(
                disposition, Instant.now().truncatedTo(ChronoUnit.MICROS));
        assertThat(retryClaim.requiresRefund()).isTrue();
        RequestRefundCommand siblingCommand = new RequestRefundCommand(
                preparation.paymentId(),
                "reservation:211:adjusted",
                20_000L,
                "RESERVATION_ADJUSTED",
                7L,
                "550e8400-e29b-41d4-a716-446655440217");
        PaymentTransactionService.RefundClaim sibling = transactions.claimRefund(
                siblingCommand, Instant.now().truncatedTo(ChronoUnit.MICROS));
        transactions.finalizeRefund(
                sibling,
                new ProviderCancellation(
                        "cancellation-sibling-211",
                        ProviderStatus.PARTIALLY_CANCELLED,
                        20_000L,
                        "KRW"),
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        DispositionResult rejected =
                paymentService.applyReservationDepositDisposition(disposition);

        assertThat(rejected.status()).isEqualTo(DispositionStatus.FAILED);
        assertThat(rejected.failureClassification())
                .isEqualTo(DispositionFailureClassification.PERMANENT);
        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), eq(failed.refundId()), eq(30_000L),
                eq("KRW"), eq("RESERVATION_DEPOSIT_DISPOSITION"));
    }

    @Test
    @DisplayName("준비·서버 검증 확정·환불 재시도가 각각 하나의 원장 결과로 수렴한다")
    void convergesRetriesToSinglePaymentAndRefundLedger() {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("123", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-1",
                        ProviderStatus.PAID,
                        30_000L,
                        "KRW"
                )
        );
        ConfirmPaymentCommand confirmation = new ConfirmPaymentCommand(
                preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-446655440001");

        PaymentResult firstConfirmation = paymentService.confirmPayment(confirmation);
        PaymentResult replayedConfirmation = paymentService.confirmPayment(confirmation);

        assertThat(firstConfirmation.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(replayedConfirmation).isEqualTo(firstConfirmation);
        verify(providerClient, times(1)).getPayment(preparation.portOnePaymentId());

        RequestRefundCommand refundCommand = new RequestRefundCommand(
                preparation.paymentId(),
                "reservation:123:cancelled",
                10_000L,
                "RESERVATION_CANCELLED",
                7L,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()),
                anyString(),
                eq(10_000L),
                eq("KRW"),
                eq("RESERVATION_CANCELLED")
        )).thenReturn(new ProviderCancellation(
                "cancellation-1", ProviderStatus.PARTIALLY_CANCELLED, 10_000L, "KRW"));

        RefundResult refund = paymentService.requestRefund(refundCommand);
        RefundResult replayedRefund = paymentService.requestRefund(refundCommand);

        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(replayedRefund).isEqualTo(refund);
        verify(providerClient, times(1)).cancelPayment(
                preparation.portOnePaymentId(),
                refund.refundId(),
                10_000L,
                "KRW",
                "RESERVATION_CANCELLED"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_ledger_entries", Long.class)).isEqualTo(4L);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentService.getConsumerPaymentHistory(
                new PaymentHistoryQuery(11L, null, 20, null)).items())
                .extracting(PaymentResult::paymentId)
                .containsExactly(preparation.paymentId());
    }

    @Test
    @DisplayName("같은 예약 snapshot을 다른 금액으로 다시 준비하지 않는다")
    void rejectsConflictingSourceSnapshot() {
        paymentService.prepareReservationDeposit(
                prepareCommand("123", 30_000L));

        assertThatThrownBy(() -> paymentService.prepareReservationDeposit(
                prepareCommand("123", 31_000L, "conflict")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.ACTIVE_SOURCE_CONFLICT);
    }

    @Test
    @DisplayName("대기열 예약금은 일반 예약금과 같은 source 참조를 공유해도 별도 source type으로 준비한다")
    void preparesWaitingReservationDepositWithSameSourceReferenceAsReservationDeposit() {
        PrepareReservationDepositCommand reservationCommand = prepareCommand("123", 30_000L);
        PaymentPreparation reservation = paymentService.prepareReservationDeposit(reservationCommand);
        PrepareWaitingReservationDepositCommand waitingCommand =
                new PrepareWaitingReservationDepositCommand(
                        reservationCommand.sourceReferenceId(),
                        reservationCommand.consumerAccountId(),
                        reservationCommand.amountMinor(),
                        reservationCommand.currency(),
                        reservationCommand.sourceExpiresAt(),
                        reservationCommand.sourcePolicyVersion(),
                        reservationCommand.idempotencyKey()
                );

        PaymentPreparation waiting = paymentService.prepareWaitingReservationDeposit(waitingCommand);
        PaymentPreparation replay = paymentService.prepareWaitingReservationDeposit(waitingCommand);

        assertThat(waiting).isEqualTo(replay);
        assertThat(waiting.paymentId()).isNotEqualTo(reservation.paymentId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payments
                WHERE source_reference_id = '123'
                  AND source_type IN ('RESERVATION_DEPOSIT', 'WAITING_RESERVATION_DEPOSIT')
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT source_type FROM payments
                WHERE payment_id = ?
                """, String.class, waiting.paymentId()))
                .isEqualTo("WAITING_RESERVATION_DEPOSIT");
    }

    @Test
    void verifiesPaidWaitingDepositSnapshotAndRejectsOrdinaryDepositWithSameReference() {
        Instant expiresAt = Instant.now().plusSeconds(3_600);
        PrepareWaitingReservationDepositCommand waitingCommand =
                new PrepareWaitingReservationDepositCommand(
                        "41", 11L, 12_000L, "KRW", expiresAt, 3L,
                        UUID.nameUUIDFromBytes("waiting:41".getBytes(StandardCharsets.UTF_8))
                                .toString());
        PaymentPreparation waiting = paymentService.prepareWaitingReservationDeposit(waitingCommand);
        when(providerClient.getPayment(waiting.portOnePaymentId())).thenReturn(new ProviderPayment(
                waiting.portOnePaymentId(), "waiting-transaction-41", ProviderStatus.PAID,
                12_000L, "KRW"));
        PaymentResult confirmed = paymentService.confirmPayment(new ConfirmPaymentCommand(
                waiting.paymentId(), 11L, waiting.portOnePaymentId(),
                UUID.nameUUIDFromBytes("waiting-confirm:41".getBytes(StandardCharsets.UTF_8))
                        .toString()));

        VerifiedWaitingReservationDeposit verified =
                paymentService.getVerifiedWaitingReservationDeposit(waiting.paymentId(), 41L, 11L);

        assertThat(confirmed.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(verified.paymentId()).isEqualTo(waiting.paymentId());
        assertThat(verified.amountMinor()).isEqualTo(12_000L);
        assertThat(verified.currency()).isEqualTo("KRW");
        assertThat(verified.sourcePolicyVersion()).isEqualTo(3L);
        assertThat(verified.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(verified.paidAt()).isNotNull();

        PaymentPreparation ordinary = paymentService.prepareReservationDeposit(
                new PrepareReservationDepositCommand(
                        "41", 11L, 12_000L, "KRW", expiresAt, 3L,
                        UUID.nameUUIDFromBytes("reservation:41".getBytes(StandardCharsets.UTF_8))
                                .toString()));
        assertThatThrownBy(() -> paymentService.getVerifiedWaitingReservationDeposit(
                ordinary.paymentId(), 41L, 11L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("준비 명령은 source 만료와 멱등 키 지문을 계약대로 검증한다")
    void enforcesPreparationExpirationAndIdempotency() {
        PrepareReservationDepositCommand command = prepareCommand("129", 30_000L);

        PaymentPreparation first = paymentService.prepareReservationDeposit(command);
        PaymentPreparation replay = paymentService.prepareReservationDeposit(command);

        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE source_reference_id = '129'", Long.class))
                .isEqualTo(1L);
        assertThatThrownBy(() -> paymentService.prepareReservationDeposit(
                new PrepareReservationDepositCommand(
                        "130", 11L, 31_000L, "KRW", Instant.now().plusSeconds(3_600), 7L,
                        command.idempotencyKey())))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThatThrownBy(() -> paymentService.prepareReservationDeposit(
                new PrepareReservationDepositCommand(
                        "131", 11L, 30_000L, "KRW", Instant.now().minusSeconds(1), 7L,
                        UUID.nameUUIDFromBytes("prepare:expired".getBytes(StandardCharsets.UTF_8))
                                .toString())))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.SOURCE_EXPIRED);
    }

    @Test
    @DisplayName("caller transaction의 준비 경합은 로컬 replay 없이 전체 재시도로 넘긴다")
    void failsPreparationRaceWithCallerTransactionWithoutLocalReplay() {
        PrepareReservationDepositCommand command = prepareCommand("154", 30_000L);
        PaymentPreparation misleadingReplay = new PaymentPreparation(
                "900000000000000154",
                "payment-reservation-900000000000000154",
                "MiriYum 예약금 154",
                30_000L,
                "KRW",
                command.sourceExpiresAt(),
                PaymentStatus.READY
        );
        doThrow(preparationConflict("uk_payments_source", 1062))
                .when(transactions).prepare(eq(command), any(Instant.class));
        doReturn(misleadingReplay).when(transactions).replayPreparation(command);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            try {
                paymentService.prepareReservationDeposit(command);
            } catch (PaymentPreparationRetryableConflictException conflict) {
                assertThat(status.isRollbackOnly()).isTrue();
                throw conflict;
            }
        }))
                .isInstanceOfSatisfying(
                        PaymentPreparationRetryableConflictException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_ledger_entries", Long.class)).isZero();
    }

    @Test
    @DisplayName("실제 준비 unique 경합은 실패 transaction 뒤 단일 Payment 원장을 replay한다")
    void replaysActualPreparationUniqueRaceAfterFailedTransaction() throws Exception {
        PrepareReservationDepositCommand command = prepareCommand("155", 30_000L);
        CountDownLatch holderPrepared = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicReference<DataIntegrityViolationException> observedConflict =
                new AtomicReference<>();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (DataIntegrityViolationException conflict) {
                observedConflict.compareAndSet(null, conflict);
                throw conflict;
            }
        }).when(transactions).prepare(eq(command), any(Instant.class));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PaymentPreparation> holder = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        PaymentPreparation prepared =
                                paymentService.prepareReservationDeposit(command);
                        holderPrepared.countDown();
                        awaitLatch(releaseHolder, "holder release");
                        return prepared;
                    }));
            assertThat(holderPrepared.await(5, TimeUnit.SECONDS)).isTrue();

            Future<PaymentPreparation> replay = executor.submit(() ->
                    paymentService.prepareReservationDeposit(command));
            try {
                awaitPaymentPreparationUniqueWait();
                assertThat(replay.isDone()).isFalse();
            } finally {
                releaseHolder.countDown();
            }

            PaymentPreparation committed = holder.get(5, TimeUnit.SECONDS);
            PaymentPreparation replayed = replay.get(5, TimeUnit.SECONDS);

            assertThat(replayed).isEqualTo(committed);
        }

        ConstraintViolationException violation = findCause(
                observedConflict.get(), ConstraintViolationException.class);
        assertThat(violation).isNotNull();
        assertThat(violation.getSQLException().getErrorCode()).isEqualTo(1062);
        assertThat(violation.getConstraintName()).isIn(
                "uk_payments_source",
                "payments.uk_payments_source",
                "uk_payments_preparation_idempotency",
                "payments.uk_payments_preparation_idempotency");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_ledger_entries", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne 금액·통화·paymentId가 준비 snapshot과 다르면 확정하지 않는다")
    void rejectsMismatchedProviderPayment() {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("123", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-1",
                        ProviderStatus.PAID,
                        29_999L,
                        "KRW"
                )
        );

        assertThatThrownBy(() -> paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-446655440002")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_ledger_entries WHERE entry_type = 'PAYMENT_CONFIRMED'",
                Long.class
        )).isZero();
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_attempts WHERE status = 'UNKNOWN'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne 결제 mapping 불일치 격리는 caller transaction rollback과 독립 커밋한다")
    void preservesConfirmationMismatchAcrossCallerRollback() {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("148", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-mismatch-148",
                        ProviderStatus.PAID,
                        29_999L,
                        "KRW"
                )
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> paymentService.confirmPayment(new ConfirmPaymentCommand(
                        preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                        "550e8400-e29b-41d4-a716-446655440148"))))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);

        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE status = 'UNKNOWN'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'PAYMENT_RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne 결제 결과 불명 격리는 이후 caller rollback에도 유지한다")
    void preservesUnknownConfirmationAcrossCallerRollback() {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("149", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId()))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored -> {
            PaymentResult result = paymentService.confirmPayment(new ConfirmPaymentCommand(
                    preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                    "550e8400-e29b-41d4-a716-446655440149"));
            assertThat(result.status()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
            throw new IllegalStateException("rollback caller");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE status = 'UNKNOWN'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'PAYMENT_RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne 결제 확정 결과는 이후 caller rollback에도 유지한다")
    void preservesFinalizedConfirmationAcrossCallerRollback() {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("150", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-150",
                        ProviderStatus.PAID,
                        30_000L,
                        "KRW"
                )
        );

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored -> {
            PaymentResult result = paymentService.confirmPayment(new ConfirmPaymentCommand(
                    preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                    "550e8400-e29b-41d4-a716-446655440150"));
            assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
            throw new IllegalStateException("rollback caller");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE status = 'PAID'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'PAYMENT_CONFIRMED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne PAY_PENDING은 confirmation 임대를 끝내고 UNKNOWN 대사 상태로 격리한다")
    void isolatesPendingProviderPaymentForReconciliation() {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("133", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(), "transaction-133",
                        ProviderStatus.PAY_PENDING, 30_000L, "KRW"));

        PaymentResult result = paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-446655440133"));

        assertThat(result.status()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE status = 'UNKNOWN'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE status = 'CONFIRMING'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("외부 결과 저장 전에 중단된 confirmation claim은 lease 만료 후 재조회 없이 대사 상태로 격리한다")
    void isolatesStaleConfirmationClaimWithoutProviderRecall() {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("134", 30_000L));
        ConfirmPaymentCommand original = new ConfirmPaymentCommand(
                preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-446655440134");
        ConfirmPaymentCommand differentKey = new ConfirmPaymentCommand(
                preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-446655440135");
        ConfirmPaymentCommand thirdKey = new ConfirmPaymentCommand(
                preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-446655440136");
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        PaymentTransactionService.ConfirmationClaim claim =
                transactions.claimConfirmation(original, claimedAt);

        assertThat(claim.requiresProviderLookup()).isTrue();
        assertThatThrownBy(() -> transactions.claimConfirmation(
                original, claimedAt.plus(Duration.ofMinutes(4))))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
        assertThatThrownBy(() -> transactions.claimConfirmation(
                differentKey, claimedAt.plus(Duration.ofMinutes(4))))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);

        PaymentTransactionService.ConfirmationClaim isolated = transactions.claimConfirmation(
                differentKey, claimedAt.plus(Duration.ofMinutes(5)));
        PaymentTransactionService.ConfirmationClaim originalReplay = transactions.claimConfirmation(
                original, claimedAt.plus(Duration.ofMinutes(6)));
        PaymentTransactionService.ConfirmationClaim isolatingKeyReplay =
                transactions.claimConfirmation(
                        differentKey, claimedAt.plus(Duration.ofMinutes(6)));
        PaymentTransactionService.ConfirmationClaim newKeyReplay = transactions.claimConfirmation(
                thirdKey, claimedAt.plus(Duration.ofMinutes(6)));

        assertThat(isolated.requiresProviderLookup()).isFalse();
        assertThat(isolated.completedResult().status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(List.of(originalReplay, isolatingKeyReplay, newKeyReplay))
                .allSatisfy(replay -> {
                    assertThat(replay.requiresProviderLookup()).isFalse();
                    assertThat(replay.completedResult().status())
                            .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
                });
        verify(providerClient, never()).getPayment(anyString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE status = 'UNKNOWN'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'PAYMENT_RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE status = 'CONFIRMING'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("서명된 Webhook 재전송은 하나의 receipt와 하나의 PortOne 조회로 수렴한다")
    void deduplicatesVerifiedWebhookBeforeProviderLookup() throws Exception {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("123", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-1",
                        ProviderStatus.PAID,
                        30_000L,
                        "KRW"
                )
        );
        String body = """
                {"type":"Transaction.Paid","timestamp":"2026-08-11T01:00:00Z","data":{"storeId":"store-1","paymentId":"%s","transactionId":"transaction-1"}}"""
                .formatted(preparation.portOnePaymentId());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "v1," + webhookSignature("msg_webhook_1", timestamp, body);

        PaymentWebhookService.WebhookResult first = webhookService.handle(
                body, "msg_webhook_1", timestamp, signature);
        PaymentWebhookService.WebhookResult replay = webhookService.handle(
                body, "msg_webhook_1", timestamp, signature);

        assertThat(first).isEqualTo(PaymentWebhookService.WebhookResult.PROCESSED);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_receipts WHERE webhook_message_id = 'msg_webhook_1'",
                Long.class
        )).isEqualTo(1L);
        verify(providerClient, times(1)).getPayment(preparation.portOnePaymentId());
    }

    @Test
    @DisplayName("처리 중 Webhook 재전송은 5xx 신호를 내고 만료된 lease 재전송으로 복구한다")
    void retriesProcessingWebhookAndRecoversExpiredLease() throws Exception {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("134", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(), "transaction-134",
                        ProviderStatus.PAID, 30_000L, "KRW"));
        String body = """
                {"type":"Transaction.Paid","timestamp":"2026-08-11T01:00:00Z","data":{"storeId":"store-1","paymentId":"%s","transactionId":"transaction-134"}}"""
                .formatted(preparation.portOnePaymentId());
        String messageId = "msg_webhook_recovery_134";
        jdbcTemplate.update("""
                INSERT INTO payment_webhook_receipts (
                    webhook_message_id, event_type, body_sha256, portone_payment_id,
                    provider_transaction_id, outcome, received_at, processed_at
                ) VALUES (?, 'Transaction.Paid', ?, ?, 'transaction-134',
                          'PROCESSING', NOW(6), NOW(6))
                """, messageId, sha256(body), preparation.portOnePaymentId());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "v1," + webhookSignature(messageId, timestamp, body);

        assertThatThrownBy(() -> webhookService.handle(body, messageId, timestamp, signature))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        verify(providerClient, times(0)).getPayment(preparation.portOnePaymentId());

        jdbcTemplate.update("""
                UPDATE payment_webhook_receipts
                   SET processed_at = DATE_SUB(NOW(6), INTERVAL 6 MINUTE)
                 WHERE webhook_message_id = ?
                """, messageId);

        assertThat(webhookService.handle(body, messageId, timestamp, signature))
                .isEqualTo(PaymentWebhookService.WebhookResult.PROCESSED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.PAID);
        verify(providerClient, times(1)).getPayment(preparation.portOnePaymentId());
    }

    @Test
    @DisplayName("RECEIVED에서 중단된 Webhook은 같은 재전송으로 서버 조회를 재개한다")
    void resumesPreviouslyReceivedWebhook() throws Exception {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("127", 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(), "transaction-127",
                        ProviderStatus.PAID, 30_000L, "KRW"));
        String body = """
                {"type":"Transaction.Paid","timestamp":"2026-08-11T01:00:00Z","data":{"storeId":"store-1","paymentId":"%s","transactionId":"transaction-127"}}"""
                .formatted(preparation.portOnePaymentId());
        jdbcTemplate.update("""
                INSERT INTO payment_webhook_receipts (
                    webhook_message_id, event_type, body_sha256, portone_payment_id,
                    provider_transaction_id, outcome, received_at
                ) VALUES (?, 'Transaction.Paid', ?, ?, 'transaction-127', 'RECEIVED', NOW(6))
                """, "msg_webhook_resume_1", sha256(body), preparation.portOnePaymentId());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "v1," + webhookSignature("msg_webhook_resume_1", timestamp, body);

        PaymentWebhookService.WebhookResult result = webhookService.handle(
                body, "msg_webhook_resume_1", timestamp, signature);

        assertThat(result).isEqualTo(PaymentWebhookService.WebhookResult.PROCESSED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.PAID);
        verify(providerClient, times(1)).getPayment(preparation.portOnePaymentId());
    }

    @Test
    @DisplayName("로컬 환불과 대응하지 않는 외부 취소 Webhook은 정상 처리하지 않고 대사 상태로 격리한다")
    void isolatesUnmatchedExternalCancellationWebhook() throws Exception {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("124");
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-2",
                        ProviderStatus.PARTIALLY_CANCELLED,
                        30_000L,
                        "KRW"
                )
        );
        String body = """
                {"type":"Transaction.PartialCancelled","timestamp":"2026-08-11T01:00:00Z","data":{"storeId":"store-1","paymentId":"%s","transactionId":"transaction-2","cancellationId":"external-cancellation-1"}}"""
                .formatted(preparation.portOnePaymentId());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "v1," + webhookSignature("msg_webhook_cancel_1", timestamp, body);

        PaymentWebhookService.WebhookResult result = webhookService.handle(
                body, "msg_webhook_cancel_1", timestamp, signature);

        assertThat(result).isEqualTo(PaymentWebhookService.WebhookResult.RECONCILIATION_REQUIRED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                WHERE entry_type = 'PAYMENT_RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("확정 중 도착한 외부 취소 Webhook은 PENDING 시도까지 UNKNOWN으로 격리한다")
    void isolatesCancellationWebhookRacingWithConfirmation() throws Exception {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("132", 30_000L));
        jdbcTemplate.update("""
                UPDATE payments
                   SET status = 'CONFIRMING', last_attempt_status = 'PENDING'
                 WHERE payment_id = ?
                """, preparation.paymentId());
        jdbcTemplate.update("""
                INSERT INTO payment_attempts (
                    payment_pk, attempt_no, principal_id, idempotency_key,
                    request_fingerprint, status, started_at
                ) SELECT payment_pk, 1, 11, '550e8400-e29b-41d4-a716-446655440099',
                         REPEAT('b', 64), 'PENDING', NOW(6)
                    FROM payments WHERE payment_id = ?
                """, preparation.paymentId());
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(), "transaction-132",
                        ProviderStatus.PARTIALLY_CANCELLED, 30_000L, "KRW"));
        String body = """
                {"type":"Transaction.Cancelled","timestamp":"2026-08-11T01:00:00Z","data":{"storeId":"store-1","paymentId":"%s","transactionId":"transaction-132","cancellationId":"external-cancellation-132"}}"""
                .formatted(preparation.portOnePaymentId());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "v1," + webhookSignature("msg_webhook_cancel_132", timestamp, body);

        PaymentWebhookService.WebhookResult result = webhookService.handle(
                body, "msg_webhook_cancel_132", timestamp, signature);

        assertThat(result).isEqualTo(PaymentWebhookService.WebhookResult.RECONCILIATION_REQUIRED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE status = 'UNKNOWN'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne 취소 요청이 처리 중이면 새 환불을 막는 대사 상태로 격리한다")
    void isolatesPendingProviderRefund() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("125");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()),
                anyString(),
                eq(10_000L),
                eq("KRW"),
                eq("RESERVATION_CANCELLED")
        )).thenReturn(new ProviderCancellation(
                "cancellation-pending-1", ProviderStatus.PAY_PENDING, 10_000L, "KRW"));

        RefundResult result = paymentService.requestRefund(new RequestRefundCommand(
                preparation.paymentId(), "reservation:125:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440010"));

        assertThat(result.status()).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("환불 timeout 대사 상태는 이후 Paid Webhook이 해제하지 않는다")
    void keepsRefundReconciliationIsolatedFromPaidWebhook() throws Exception {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("135");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(10_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED")
        )).thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));

        RefundResult refund = paymentService.requestRefund(new RequestRefundCommand(
                preparation.paymentId(), "reservation:135:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440135"));
        assertThat(refund.status()).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);

        String body = """
                {"type":"Transaction.Paid","timestamp":"2026-08-11T01:00:00Z","data":{"storeId":"store-1","paymentId":"%s","transactionId":"transaction-135"}}"""
                .formatted(preparation.portOnePaymentId());
        String messageId = "msg_webhook_paid_after_refund_135";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "v1," + webhookSignature(messageId, timestamp, body);

        assertThat(webhookService.handle(body, messageId, timestamp, signature))
                .isEqualTo(PaymentWebhookService.WebhookResult.RECONCILIATION_REQUIRED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        verify(providerClient, times(1)).getPayment(preparation.portOnePaymentId());
    }

    @Test
    @DisplayName("환불 timeout 대사 상태는 새 Consumer confirmation 키로도 해제하지 않는다")
    void keepsRefundReconciliationIsolatedFromConsumerConfirmation() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("139");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(10_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED")
        )).thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        paymentService.requestRefund(new RequestRefundCommand(
                preparation.paymentId(), "reservation:139:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440139"));

        PaymentResult result = paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(), 11L, preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-446655440140"));

        assertThat(result.status()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds WHERE status = 'RECONCILIATION_REQUIRED'",
                Long.class)).isEqualTo(1L);
        verify(providerClient, times(1)).getPayment(preparation.portOnePaymentId());
    }

    @Test
    @DisplayName("PortOne이 취소 실패를 명시하면 환불만 FAILED로 종결하고 결제 원장은 PAID를 유지한다")
    void recordsExplicitProviderRefundFailure() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("126");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()),
                anyString(),
                eq(10_000L),
                eq("KRW"),
                eq("RESERVATION_CANCELLED")
        )).thenReturn(new ProviderCancellation(
                "cancellation-failed-1", ProviderStatus.FAILED, 10_000L, "KRW"));

        RefundResult result = paymentService.requestRefund(new RequestRefundCommand(
                preparation.paymentId(), "reservation:126:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440011"));

        assertThat(result.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                WHERE entry_type = 'REFUND_FAILED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne 환불 금액 불일치는 원장을 격리하고 다른 키의 중복 취소 호출을 차단한다")
    void isolatesProviderRefundMappingMismatch() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("128");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(10_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED")
        )).thenReturn(new ProviderCancellation(
                "cancellation-mismatch-1", ProviderStatus.PARTIALLY_CANCELLED, 9_999L, "KRW"));
        RequestRefundCommand first = new RequestRefundCommand(
                preparation.paymentId(), "reservation:128:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440012");

        assertThatThrownBy(() -> paymentService.requestRefund(first))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_refunds WHERE status = 'RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);

        assertThatThrownBy(() -> paymentService.requestRefund(new RequestRefundCommand(
                preparation.paymentId(), "reservation:128:cancelled:retry", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440013")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.INVALID_STATE_TRANSITION);
        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(10_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED"));
    }

    @Test
    @DisplayName("PortOne 환불 mapping 불일치 격리는 caller transaction rollback과 독립 커밋한다")
    void preservesRefundMismatchAcrossCallerRollback() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("151");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(10_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED")
        )).thenReturn(new ProviderCancellation(
                "cancellation-mismatch-151",
                ProviderStatus.PARTIALLY_CANCELLED,
                9_999L,
                "KRW"
        ));
        RequestRefundCommand command = new RequestRefundCommand(
                preparation.paymentId(), "reservation:151:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440151");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> paymentService.requestRefund(command)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);

        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_refunds
                 WHERE status = 'RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne 환불 결과 불명 격리는 이후 caller rollback에도 유지한다")
    void preservesUnknownRefundAcrossCallerRollback() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("152");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(10_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED")
        )).thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        RequestRefundCommand command = new RequestRefundCommand(
                preparation.paymentId(), "reservation:152:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440152");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored -> {
            RefundResult result = paymentService.requestRefund(command);
            assertThat(result.status()).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
            throw new IllegalStateException("rollback caller");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_refunds
                 WHERE status = 'RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PortOne 환불 완료 결과는 이후 caller rollback에도 유지한다")
    void preservesFinalizedRefundAcrossCallerRollback() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("153");
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(10_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED")
        )).thenReturn(new ProviderCancellation(
                "cancellation-153",
                ProviderStatus.PARTIALLY_CANCELLED,
                10_000L,
                "KRW"
        ));
        RequestRefundCommand command = new RequestRefundCommand(
                preparation.paymentId(), "reservation:153:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440153");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored -> {
            RefundResult result = paymentService.requestRefund(command);
            assertThat(result.status()).isEqualTo(RefundStatus.COMPLETED);
            throw new IllegalStateException("rollback caller");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds WHERE status = 'COMPLETED'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_COMPLETED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("동시 확정 재시도는 두 번째 PortOne 호출 없이 충돌로 종료한 뒤 최초 결과로 수렴한다")
    void preventsConcurrentDuplicateProviderLookup() throws Exception {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand("123", 30_000L));
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenAnswer(invocation -> {
            providerEntered.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("provider release timed out");
            }
            return new ProviderPayment(
                    preparation.portOnePaymentId(),
                    "transaction-1",
                    ProviderStatus.PAID,
                    30_000L,
                    "KRW"
            );
        });
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                preparation.paymentId(),
                11L,
                preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-446655440003"
        );

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PaymentResult> first = executor.submit(() -> paymentService.confirmPayment(command));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> concurrent = executor.submit(() -> {
                try {
                    paymentService.confirmPayment(command);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            Throwable concurrentFailure = concurrent.get(5, TimeUnit.SECONDS);
            assertThat(concurrentFailure)
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getErrorCode())
                    .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);

            releaseProvider.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.PAID);
        }

        verify(providerClient, times(1)).getPayment(preparation.portOnePaymentId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("서로 다른 동시 환불은 PROCESSING 금액을 예약해 원 승인액 초과 외부 취소를 막는다")
    void reservesProcessingRefundAmountAcrossConcurrentClaims() throws Exception {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("136");
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        when(providerClient.cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(20_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED")
        )).thenAnswer(invocation -> {
            int call = providerCalls.incrementAndGet();
            if (call == 1) {
                providerEntered.countDown();
                if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("provider release timed out");
                }
            }
            return new ProviderCancellation(
                    "cancellation-concurrent-" + call,
                    ProviderStatus.PARTIALLY_CANCELLED,
                    20_000L,
                    "KRW"
            );
        });
        RequestRefundCommand firstCommand = new RequestRefundCommand(
                preparation.paymentId(), "reservation:136:first", 20_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440136");
        RequestRefundCommand secondCommand = new RequestRefundCommand(
                preparation.paymentId(), "reservation:136:second", 20_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440137");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<RefundResult> first = executor.submit(
                    () -> paymentService.requestRefund(firstCommand));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> concurrent = executor.submit(() -> {
                try {
                    paymentService.requestRefund(secondCommand);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            try {
                assertThat(concurrent.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ServiceException.class)
                        .extracting(error -> ((ServiceException) error).getErrorCode())
                        .isEqualTo(PaymentErrorCode.REFUND_AMOUNT_EXCEEDED);
            } finally {
                releaseProvider.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(RefundStatus.COMPLETED);
        }

        verify(providerClient, times(1)).cancelPayment(
                eq(preparation.portOnePaymentId()), anyString(), eq(20_000L),
                eq("KRW"), eq("RESERVATION_CANCELLED"));
    }

    @Test
    @DisplayName("외부 호출 전에 중단된 환불 claim은 lease 만료 후 재호출 없이 대사 상태로 격리한다")
    void isolatesStaleRefundClaimWithoutRetryingProviderCancellation() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("140");
        RequestRefundCommand command = new RequestRefundCommand(
                preparation.paymentId(), "reservation:140:cancelled", 10_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440141");
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        PaymentTransactionService.RefundClaim claim = transactions.claimRefund(command, claimedAt);
        PaymentTransactionService.RefundClaim activeLeaseReplay = transactions.claimRefund(
                command, claimedAt.plusSeconds(299));
        PaymentTransactionService.RefundClaim staleLeaseReplay = transactions.claimRefund(
                command, claimedAt.plusSeconds(301));

        assertThat(claim.requiresProviderCall()).isTrue();
        assertThat(activeLeaseReplay.completedResult().status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(staleLeaseReplay.completedResult().status())
                .isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                WHERE entry_type = 'REFUND_RECONCILIATION_REQUIRED'
                  AND event_key = ?
                """, Long.class, "refund-reconciliation:" + claim.refundId())).isEqualTo(1L);
        verify(providerClient, never()).cancelPayment(
                anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("다른 키 요청도 만료된 refund claim을 새 외부 호출 없이 대사 상태로 격리한다")
    void isolatesStaleRefundClaimDiscoveredByDifferentKey() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("147");
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RequestRefundCommand abandoned = new RequestRefundCommand(
                preparation.paymentId(), "reservation:147:cancelled", 30_000L,
                "RESERVATION_CANCELLED", 7L,
                "550e8400-e29b-41d4-a716-446655440147");
        RequestRefundCommand differentKey = new RequestRefundCommand(
                preparation.paymentId(), "reservation:147:adjusted", 10_000L,
                "RESERVATION_ADJUSTED", 7L,
                "550e8400-e29b-41d4-a716-446655440148");

        PaymentTransactionService.RefundClaim claim =
                transactions.claimRefund(abandoned, claimedAt);
        jdbcTemplate.update("""
                UPDATE payment_refunds
                   SET processing_started_at = DATE_SUB(NOW(6), INTERVAL 5 MINUTE)
                 WHERE refund_id = ?
                """, claim.refundId());

        assertThat(claim.requiresProviderCall()).isTrue();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> paymentService.requestRefund(differentKey)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.INVALID_STATE_TRANSITION);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_refunds
                 WHERE refund_id = ? AND status = 'RECONCILIATION_REQUIRED'
                """, Long.class, claim.refundId())).isEqualTo(1L);
        assertThatThrownBy(() -> paymentService.requestRefund(differentKey))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.INVALID_STATE_TRANSITION);
        verify(providerClient, never()).cancelPayment(
                anyString(), anyString(), anyLong(), anyString(), anyString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_ledger_entries
                 WHERE entry_type = 'REFUND_RECONCILIATION_REQUIRED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("sibling 환불이 미해결이면 다른 환불 성공 후에도 결제 대사 상태를 유지한다")
    void keepsPaymentReconciliationWhenSiblingRefundRemainsUnresolved() {
        PaymentPreparation preparation = prepareAndConfirmPaidPayment("141");
        Instant claimedAt = Instant.now();
        PaymentTransactionService.RefundClaim first = transactions.claimRefund(
                new RequestRefundCommand(
                        preparation.paymentId(), "reservation:141:first", 10_000L,
                        "RESERVATION_CANCELLED", 7L,
                        "550e8400-e29b-41d4-a716-446655440142"),
                claimedAt);
        PaymentTransactionService.RefundClaim second = transactions.claimRefund(
                new RequestRefundCommand(
                        preparation.paymentId(), "reservation:141:second", 10_000L,
                        "RESERVATION_CANCELLED", 7L,
                        "550e8400-e29b-41d4-a716-446655440143"),
                claimedAt.plusSeconds(1));

        RefundResult unresolved = transactions.markRefundUnknown(first, claimedAt.plusSeconds(2));
        RefundResult completed = transactions.finalizeRefund(
                second,
                new ProviderCancellation(
                        "cancellation-sibling-141",
                        ProviderStatus.PARTIALLY_CANCELLED,
                        10_000L,
                        "KRW"),
                claimedAt.plusSeconds(3));

        assertThat(unresolved.status()).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
        assertThat(completed.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(paymentService.getOwnedPayment(preparation.paymentId(), "11").status())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_refunds
                WHERE payment_pk = (SELECT payment_pk FROM payments WHERE payment_id = ?)
                  AND status = 'RECONCILIATION_REQUIRED'
                """, Long.class, preparation.paymentId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("다른 일반 사용자의 유효한 결제 이력 cursor는 PAYMENT_005로 거부한다")
    void rejectsHistoryCursorAcrossConsumers() {
        paymentService.prepareReservationDeposit(prepareCommand("137", 30_000L));
        paymentService.prepareReservationDeposit(prepareCommand("138", 30_000L));
        PaymentHistoryQuery firstPage = new PaymentHistoryQuery(11L, null, 1, null);
        String ownerCursor = paymentService.getConsumerPaymentHistory(firstPage).nextCursor();

        assertThat(ownerCursor).isNotBlank();
        assertThatThrownBy(() -> paymentService.getConsumerPaymentHistory(
                new PaymentHistoryQuery(12L, null, 1, ownerCursor)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.INVALID_HISTORY_CURSOR);
    }

    private PaymentPreparation prepareAndConfirmPaidPayment(String reservationReferenceId) {
        PaymentPreparation preparation = paymentService.prepareReservationDeposit(
                prepareCommand(reservationReferenceId, 30_000L));
        when(providerClient.getPayment(preparation.portOnePaymentId())).thenReturn(
                new ProviderPayment(
                        preparation.portOnePaymentId(),
                        "transaction-" + reservationReferenceId,
                        ProviderStatus.PAID,
                        30_000L,
                        "KRW"
                )
        );
        paymentService.confirmPayment(new ConfirmPaymentCommand(
                preparation.paymentId(),
                11L,
                preparation.portOnePaymentId(),
                "550e8400-e29b-41d4-a716-" + String.format("%012d", Long.parseLong(reservationReferenceId))
        ));
        return preparation;
    }

    private static ApplyReservationDepositDispositionCommand dispositionCommand(
            String paymentId,
            String sourceEventId,
            String correctsSourceEventId,
            int targetRefundRateBasisPoints,
            String idempotencyKey
    ) {
        return new ApplyReservationDepositDispositionCommand(
                paymentId,
                sourceEventId,
                correctsSourceEventId == null
                        ? "RESERVATION_CANCELLED"
                        : "RESERVATION_CANCELLATION_CORRECTED",
                correctsSourceEventId,
                2L,
                "CONSUMER",
                targetRefundRateBasisPoints,
                idempotencyKey);
    }

    private void awaitPaymentPreparationUniqueWait() {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM performance_schema.data_lock_waits AS wait_edge
                     JOIN performance_schema.data_locks AS requested_lock
                       ON requested_lock.ENGINE = wait_edge.ENGINE
                      AND requested_lock.ENGINE_LOCK_ID = wait_edge.REQUESTING_ENGINE_LOCK_ID
                     WHERE requested_lock.OBJECT_SCHEMA = DATABASE()
                       AND requested_lock.OBJECT_NAME = 'payments'
                       AND requested_lock.INDEX_NAME IN (
                           'uk_payments_source',
                           'uk_payments_preparation_idempotency'
                       )
                     """)) {
            while (System.nanoTime() < deadlineNanos) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "unable to observe Payment preparation unique lock wait", exception);
        }
        throw new AssertionError("Payment preparation never entered a unique lock wait");
    }

    private static void awaitLatch(CountDownLatch latch, String boundary) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError(boundary + " was not reached within 5 seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting " + boundary, exception);
        }
    }

    private static DataIntegrityViolationException preparationConflict(
            String constraintName,
            int mysqlCode
    ) {
        return new DataIntegrityViolationException(
                "payment preparation conflict",
                new ConstraintViolationException(
                        "payment constraint conflict",
                        new SQLException("duplicate", "23000", mysqlCode),
                        "insert into payments",
                        constraintName));
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static String webhookSignature(String messageId, String timestamp, String body)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                Base64.getDecoder().decode("dGVzdC1zZWNyZXQ="),
                "HmacSHA256"
        ));
        return Base64.getEncoder().encodeToString(mac.doFinal(
                (messageId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static PrepareReservationDepositCommand prepareCommand(
            String sourceReferenceId,
            long amountMinor
    ) {
        return prepareCommand(sourceReferenceId, amountMinor, "default");
    }

    private static PrepareReservationDepositCommand prepareCommand(
            String sourceReferenceId,
            long amountMinor,
            String keySuffix
    ) {
        return new PrepareReservationDepositCommand(
                sourceReferenceId,
                11L,
                amountMinor,
                "KRW",
                Instant.now().plusSeconds(3_600),
                7L,
                UUID.nameUUIDFromBytes(("prepare:" + sourceReferenceId + ":" + keySuffix)
                        .getBytes(StandardCharsets.UTF_8)).toString()
        );
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
