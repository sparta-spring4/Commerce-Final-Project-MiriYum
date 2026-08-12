package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Tag("integration-shard-a")
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
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.0.40"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactionService transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentWebhookService webhookService;

    @MockitoBean
    private PaymentProviderClient providerClient;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM payment_webhook_receipts");
        jdbcTemplate.execute("DELETE FROM payment_ledger_entries");
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
        Instant claimedAt = Instant.now();

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
        Instant claimedAt = Instant.now();

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
