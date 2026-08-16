package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.miriyum.domain.payment.dto.PaymentContracts.VerifiedWaitingReservationDeposit;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "uk_payments_source",
            "payments.uk_payments_source",
            "uk_payments_preparation_idempotency",
            "payments.uk_payments_preparation_idempotency"
    })
    @DisplayName("caller transaction이 없는 준비 경합은 저장된 동일 결과를 재생한다")
    void replaysPreparationRaceWithoutCallerTransaction(String constraintName) {
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
                .thenThrow(preparationConflict(constraintName, 1062));
        when(transactions.replayPreparation(command)).thenReturn(expected);

        PaymentPreparation result = paymentService.prepareReservationDeposit(command);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("caller transaction의 승인 준비 경합은 DB 원인 없는 전용 신호로 전달한다")
    void signalsApprovedPreparationConflictToCallerTransaction() {
        PrepareReservationDepositCommand command = prepareCommand();
        when(transactions.prepare(command, NOW))
                .thenThrow(preparationConflict("uk_payments_source", 1062));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> paymentService.prepareReservationDeposit(command))
                    .isInstanceOfSatisfying(
                            PaymentPreparationRetryableConflictException.class,
                            exception -> {
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                                assertThat(exception.getCause()).isNull();
                            });
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("비승인 constraint의 MySQL 1062는 준비 replay 신호가 아니다")
    void rejectsUnapprovedPreparationConstraint() {
        PrepareReservationDepositCommand command = prepareCommand();
        when(transactions.prepare(command, NOW))
                .thenThrow(preparationConflict("uk_payments_payment_id", 1062));

        assertThatThrownBy(() -> paymentService.prepareReservationDeposit(command))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception)
                            .isNotInstanceOf(PaymentPreparationRetryableConflictException.class);
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                });
    }

    @Test
    @DisplayName("constraint 이름이 없는 MySQL 1062는 준비 replay 신호가 아니다")
    void rejectsMysqlDuplicateWithoutConstraintName() {
        PrepareReservationDepositCommand command = prepareCommand();
        when(transactions.prepare(command, NOW))
                .thenThrow(preparationConflict(null, 1062));

        assertThatThrownBy(() -> paymentService.prepareReservationDeposit(command))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception)
                            .isNotInstanceOf(PaymentPreparationRetryableConflictException.class);
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                });
    }

    @Test
    @DisplayName("승인 constraint여도 MySQL 1062가 아니면 준비 replay 신호가 아니다")
    void rejectsApprovedConstraintWithDifferentMysqlCode() {
        PrepareReservationDepositCommand command = prepareCommand();
        when(transactions.prepare(command, NOW))
                .thenThrow(preparationConflict("uk_payments_source", 1452));

        assertThatThrownBy(() -> paymentService.prepareReservationDeposit(command))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception)
                            .isNotInstanceOf(PaymentPreparationRetryableConflictException.class);
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                });
    }

    @Test
    @DisplayName("message-only 준비 무결성 오류는 저장 결과로 추측해 replay하지 않는다")
    void rejectsMessageOnlyPreparationConflictWithoutReplay() {
        PrepareReservationDepositCommand command = prepareCommand();
        when(transactions.prepare(command, NOW))
                .thenThrow(new DataIntegrityViolationException("uk_payments_source"));

        assertThatThrownBy(() -> paymentService.prepareReservationDeposit(command))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
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
    void returnsOnlyVerifiedPaidWaitingReservationDeposit() {
        VerifiedWaitingReservationDeposit expected =
                new VerifiedWaitingReservationDeposit(
                        PAYMENT_ID, 30_000L, "KRW", 7L, PaymentStatus.PAID, NOW);
        when(transactions.getVerifiedWaitingReservationDeposit(
                PAYMENT_ID, 123L, 11L)).thenReturn(expected);

        VerifiedWaitingReservationDeposit result =
                paymentService.getVerifiedWaitingReservationDeposit(
                        PAYMENT_ID, 123L, 11L);

        assertThat(result).isEqualTo(expected);
        assertThat(recordComponentNames(VerifiedWaitingReservationDeposit.class)).containsExactly(
                "paymentId", "amountMinor", "currency", "sourcePolicyVersion", "status", "paidAt");
        verify(transactions).getVerifiedWaitingReservationDeposit(PAYMENT_ID, 123L, 11L);
    }

    @Test
    void returnsLockedCompletableWaitingDepositOnlyForCurrentPaidWithoutRefunds() {
        VerifiedWaitingReservationDeposit expected =
                new VerifiedWaitingReservationDeposit(
                        PAYMENT_ID, 30_000L, "KRW", 7L, PaymentStatus.PAID, NOW);
        when(transactions.getCompletableWaitingReservationDeposit(
                PAYMENT_ID, 123L, 11L)).thenReturn(expected);

        assertThat(paymentService.getCompletableWaitingReservationDeposit(
                PAYMENT_ID, 123L, 11L)).isEqualTo(expected);

        Payment paid = payment("WAITING_RESERVATION_DEPOSIT", "123", 11L, true);
        assertThat(PaymentTransactionService.requireCompletableWaitingReservationDeposit(
                paid, PAYMENT_ID, 123L, 11L, false)).isSameAs(paid);
        assertThatThrownBy(() ->
                PaymentTransactionService.requireCompletableWaitingReservationDeposit(
                        paid, PAYMENT_ID, 123L, 11L, true))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.INVALID_STATE_TRANSITION));

        Payment partiallyRefunded = payment("WAITING_RESERVATION_DEPOSIT", "123", 11L, true);
        partiallyRefunded.applyCompletedRefund(10_000L, NOW.plusSeconds(1));
        assertThatThrownBy(() ->
                PaymentTransactionService.requireCompletableWaitingReservationDeposit(
                        partiallyRefunded, PAYMENT_ID, 123L, 11L, false))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void rejectsWrongWaitingSourceOwnerReferenceOrState() {
        Payment valid = payment("WAITING_RESERVATION_DEPOSIT", "123", 11L, true);
        assertThat(PaymentTransactionService.requireVerifiedWaitingReservationDeposit(
                valid, PAYMENT_ID, 123L, 11L)).isSameAs(valid);

        Payment partiallyRefunded = payment("WAITING_RESERVATION_DEPOSIT", "123", 11L, true);
        partiallyRefunded.applyCompletedRefund(10_000L, NOW.plusSeconds(1));
        assertThat(PaymentTransactionService.requireVerifiedWaitingReservationDeposit(
                partiallyRefunded, PAYMENT_ID, 123L, 11L)).isSameAs(partiallyRefunded);

        Payment refunded = payment("WAITING_RESERVATION_DEPOSIT", "123", 11L, true);
        refunded.applyCompletedRefund(30_000L, NOW.plusSeconds(1));
        assertThat(PaymentTransactionService.requireVerifiedWaitingReservationDeposit(
                refunded, PAYMENT_ID, 123L, 11L)).isSameAs(refunded);

        Payment reconciliation = payment("WAITING_RESERVATION_DEPOSIT", "123", 11L, true);
        reconciliation.markRefundReconciliationRequired(NOW.plusSeconds(1));
        assertThat(PaymentTransactionService.requireVerifiedWaitingReservationDeposit(
                reconciliation, PAYMENT_ID, 123L, 11L)).isSameAs(reconciliation);

        assertRejected(payment("RESERVATION_DEPOSIT", "123", 11L, true), 123L, 11L);
        assertRejected(payment("WAITING_RESERVATION_DEPOSIT", "124", 11L, true), 123L, 11L);
        assertRejected(payment("WAITING_RESERVATION_DEPOSIT", "123", 12L, true), 123L, 11L);
        assertRejected(payment("WAITING_RESERVATION_DEPOSIT", "123", 11L, false), 123L, 11L);
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

    private static PrepareReservationDepositCommand prepareCommand() {
        return new PrepareReservationDepositCommand(
                "123",
                11L,
                30_000L,
                "KRW",
                NOW.plusSeconds(600),
                7L,
                "550e8400-e29b-41d4-a716-446655440123"
        );
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

    private static Payment payment(
            String sourceType,
            String sourceReferenceId,
            long consumerAccountId,
            boolean paid
    ) {
        Payment payment = Payment.prepare(
                PAYMENT_ID,
                sourceType,
                sourceReferenceId,
                7L,
                NOW.plusSeconds(600),
                "550e8400-e29b-41d4-a716-446655440128",
                "a".repeat(64),
                consumerAccountId,
                30_000L,
                "KRW",
                PORTONE_PAYMENT_ID,
                "Waiting deposit",
                NOW.minusSeconds(60));
        if (paid) {
            payment.beginConfirmation(NOW.minusSeconds(30));
            payment.markPaid("transaction-1", NOW);
        }
        return payment;
    }

    private static void assertRejected(
            Payment payment,
            long waitingTeamId,
            long consumerAccountId
    ) {
        assertThatThrownBy(() -> PaymentTransactionService.requireVerifiedWaitingReservationDeposit(
                payment, PAYMENT_ID, waitingTeamId, consumerAccountId))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }
}
