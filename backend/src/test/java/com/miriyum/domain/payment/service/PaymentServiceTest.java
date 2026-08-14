package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T01:00:00Z");
    private static final String PAYMENT_ID = "900000000000000001";
    private static final String PORTONE_PAYMENT_ID = "payment-reservation-900000000000000001";

    @Mock
    private PaymentTransactionService transactions;

    @Mock
    private PaymentProviderClient providerClient;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                transactions,
                providerClient,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("caller transaction이 없는 준비 경합은 저장된 동일 결과를 재생한다")
    void replaysPreparationRaceWithoutCallerTransaction() {
        PrepareReservationDepositCommand command = new PrepareReservationDepositCommand(
                "123",
                11L,
                30_000L,
                "KRW",
                NOW.plusSeconds(600),
                7L,
                "550e8400-e29b-41d4-a716-446655440123"
        );
        PaymentPreparation expected = new PaymentPreparation(
                PAYMENT_ID,
                PORTONE_PAYMENT_ID,
                "MiriYum 예약금 123",
                30_000L,
                "KRW",
                command.sourceExpiresAt(),
                PaymentStatus.READY
        );
        when(transactions.prepare(command, NOW))
                .thenThrow(new DataIntegrityViolationException("uk_payments_source"));
        when(transactions.replayPreparation(command)).thenReturn(expected);

        PaymentPreparation result = paymentService.prepareReservationDeposit(command);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("대기열 예약금 준비 경합은 대기열 source에 저장된 동일 결과를 재생한다")
    void replaysWaitingReservationPreparationRaceWithoutCallerTransaction() {
        PrepareWaitingReservationDepositCommand command =
                new PrepareWaitingReservationDepositCommand(
                        "123",
                        11L,
                        30_000L,
                        "KRW",
                        NOW.plusSeconds(600),
                        7L,
                        "550e8400-e29b-41d4-a716-446655440124"
                );
        PaymentPreparation expected = new PaymentPreparation(
                PAYMENT_ID,
                PORTONE_PAYMENT_ID,
                "MiriYum 예약금 123",
                30_000L,
                "KRW",
                command.sourceExpiresAt(),
                PaymentStatus.READY
        );
        when(transactions.prepareWaitingReservationDeposit(command, NOW))
                .thenThrow(new DataIntegrityViolationException("uk_payments_source"));
        when(transactions.replayWaitingReservationDeposit(command)).thenReturn(expected);

        PaymentPreparation result = paymentService.prepareWaitingReservationDeposit(command);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("브라우저 결과가 아니라 PortOne 단건 조회 결과를 transaction 경계에 전달해 확정한다")
    void confirmsFromProviderLookup() {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                PAYMENT_ID,
                11L,
                PORTONE_PAYMENT_ID,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        PaymentTransactionService.ConfirmationClaim claim =
                PaymentTransactionService.ConfirmationClaim.requiresLookup(
                        PAYMENT_ID,
                        PORTONE_PAYMENT_ID,
                        30_000L,
                        "KRW"
                );
        ProviderPayment providerPayment = new ProviderPayment(
                PORTONE_PAYMENT_ID,
                "transaction-1",
                ProviderStatus.PAID,
                30_000L,
                "KRW"
        );
        PaymentResult expected = paidResult();
        when(transactions.claimConfirmation(command, NOW)).thenReturn(claim);
        when(providerClient.getPayment(PORTONE_PAYMENT_ID)).thenReturn(providerPayment);
        when(transactions.finalizeConfirmation(claim, providerPayment, NOW)).thenReturn(expected);

        PaymentResult result = paymentService.confirmPayment(command);

        assertThat(result).isEqualTo(expected);
        verify(providerClient).getPayment(PORTONE_PAYMENT_ID);
        verify(transactions).finalizeConfirmation(claim, providerPayment, NOW);
    }

    @Test
    @DisplayName("이미 확정된 재시도는 PortOne을 다시 호출하지 않고 저장 결과를 반환한다")
    void replaysCompletedConfirmationWithoutProviderCall() {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                PAYMENT_ID,
                11L,
                PORTONE_PAYMENT_ID,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        PaymentResult expected = paidResult();
        PaymentTransactionService.ConfirmationClaim claim =
                PaymentTransactionService.ConfirmationClaim.completed(expected);
        when(transactions.claimConfirmation(command, NOW)).thenReturn(claim);

        PaymentResult result = paymentService.confirmPayment(command);

        assertThat(result).isEqualTo(expected);
        verify(providerClient, never()).getPayment(PORTONE_PAYMENT_ID);
    }

    @Test
    @DisplayName("PortOne 결과가 불명확하면 성공으로 꾸미지 않고 대사 필요 상태를 반환한다")
    void isolatesProviderTimeoutForReconciliation() {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                PAYMENT_ID,
                11L,
                PORTONE_PAYMENT_ID,
                "550e8400-e29b-41d4-a716-446655440000"
        );
        PaymentTransactionService.ConfirmationClaim claim =
                PaymentTransactionService.ConfirmationClaim.requiresLookup(
                        PAYMENT_ID,
                        PORTONE_PAYMENT_ID,
                        30_000L,
                        "KRW"
                );
        PaymentResult expected = reconciliationResult();
        when(transactions.claimConfirmation(command, NOW)).thenReturn(claim);
        when(providerClient.getPayment(PORTONE_PAYMENT_ID))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        when(transactions.markConfirmationUnknown(claim, NOW)).thenReturn(expected);

        PaymentResult result = paymentService.confirmPayment(command);

        assertThat(result.status()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        verify(transactions).markConfirmationUnknown(claim, NOW);
        verify(transactions, never()).finalizeConfirmation(claim, null, NOW);
    }

    @Test
    @DisplayName("Reservation 공개 준비·환불 DTO는 Payment 정본의 scalar 필드만 정확히 노출한다")
    void exposesCanonicalReservationPaymentContracts() {
        assertThat(recordComponentNames(PrepareReservationDepositCommand.class)).containsExactly(
                "sourceReferenceId", "consumerAccountId", "amountMinor", "currency",
                "sourceExpiresAt", "sourcePolicyVersion", "idempotencyKey");
        assertThat(recordComponentNames(PaymentPreparation.class)).containsExactly(
                "paymentId", "portOnePaymentId", "orderName", "amountMinor", "currency",
                "sourceExpiresAt", "status");
        assertThat(recordComponentNames(RequestRefundCommand.class)).containsExactly(
                "paymentId", "sourceEventId", "refundAmountMinor", "reasonCode",
                "policyVersion", "idempotencyKey");
        assertThat(recordComponentNames(RefundResult.class)).containsExactly(
                "refundId", "paymentId", "requestedAmountMinor", "completedAmountMinor",
                "cumulativeRefundedAmountMinor", "remainingRefundableAmountMinor", "currency",
                "status", "requestedAt", "completedAt");
    }

    private static List<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .toList();
    }

    private static PaymentResult paidResult() {
        return new PaymentResult(
                PAYMENT_ID,
                "123",
                30_000L,
                0L,
                30_000L,
                "KRW",
                PaymentStatus.PAID,
                PaymentAttemptStatus.PAID,
                NOW.minusSeconds(60),
                NOW,
                NOW,
                List.of()
        );
    }

    private static PaymentResult reconciliationResult() {
        return new PaymentResult(
                PAYMENT_ID,
                "123",
                30_000L,
                0L,
                30_000L,
                "KRW",
                PaymentStatus.RECONCILIATION_REQUIRED,
                PaymentAttemptStatus.UNKNOWN,
                NOW.minusSeconds(60),
                null,
                NOW,
                List.of()
        );
    }
}
