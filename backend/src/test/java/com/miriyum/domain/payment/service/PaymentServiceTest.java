package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.payment.dto.PaymentContracts.ApplyReservationDepositDispositionCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionFailureClassification;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.GetReservationDepositDispositionQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.StoreReservationPaymentSnapshot;
import com.miriyum.domain.payment.dto.PaymentContracts.StoreReservationRefundSnapshot;
import com.miriyum.domain.payment.dto.PaymentContracts.VerifiedWaitingReservationDeposit;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.InspectManualRecoveryQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ReconcileManualRecoveryCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ReconcileRefundResultQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RequestManualRecoveryRefundCommand;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.entity.PaymentRefund;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
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
import java.util.Optional;
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
    private static final String SOURCE_REFERENCE_ID = "123";
    private static final String PORTONE_PAYMENT_ID = "payment-reservation-900000000000000001";

    @Mock
    private PaymentTransactionService transactions;

    @Mock
    private PaymentProviderClient providerClient;

    @Mock
    private PaymentRecoveryTransactionService recoveryTransactions;

    @Mock
    private com.miriyum.domain.payment.repository.PaymentRepository payments;

    @Mock
    private com.miriyum.domain.payment.repository.PaymentRefundRepository refunds;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                transactions,
                providerClient,
                recoveryTransactions,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("수동 복구 재조회는 provider 취소를 재전송하지 않고 기존 결과만 원장에 반영한다")
    void reconcilesManualRefundByLookupWithoutResendingCancellation() {
        ReconcileManualRecoveryCommand command = new ReconcileManualRecoveryCommand(
                "21", 3L, 4L, 5L);
        PaymentTransactionService.RefundClaim refundClaim =
                PaymentTransactionService.RefundClaim.requiresCall(
                        "910000000000000001", PAYMENT_ID, PORTONE_PAYMENT_ID,
                        100_000L, "KRW", "RESERVATION_CANCELLED");
        var claim = PaymentRecoveryTransactionService.ManualReconciliationClaim
                .refund("21", refundClaim, 300_000L);
        var cancellation = new ProviderCancellation(
                "cancel-1", ProviderStatus.PARTIALLY_CANCELLED,
                100_000L, "KRW",
                PaymentProviderClient.cancellationReason(
                        "RESERVATION_CANCELLED", "910000000000000001"));
        var providerPayment = new ProviderPayment(
                PORTONE_PAYMENT_ID, "transaction-1", ProviderStatus.PARTIALLY_CANCELLED,
                300_000L, "KRW", List.of(cancellation));
        ManualRecoveryInspection expected = mock(ManualRecoveryInspection.class);
        when(recoveryTransactions.claimReconciliation(command)).thenReturn(claim);
        when(providerClient.getPayment(PORTONE_PAYMENT_ID)).thenReturn(providerPayment);
        when(recoveryTransactions.inspect(new InspectManualRecoveryQuery("21")))
                .thenReturn(expected);

        var result = paymentService.reconcileManualRecovery(command);

        assertThat(result).isSameAs(expected);
        verify(transactions).finalizeRefund(refundClaim, cancellation, NOW);
        verify(providerClient, never()).cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 100_000L,
                "KRW", "RESERVATION_CANCELLED");
    }

    @Test
    @DisplayName("자동 환불 대사는 provider GET만 호출하고 일치한 취소를 원장에 반영한다")
    void reconcilesAutomaticRefundByLookupWithoutResendingCancellation() {
        ReconcileRefundResultQuery query = new ReconcileRefundResultQuery(
                PAYMENT_ID, "reservation:1:cancelled", 100_000L, "KRW");
        PaymentTransactionService.RefundClaim refundClaim =
                PaymentTransactionService.RefundClaim.requiresCall(
                        "910000000000000001", PAYMENT_ID, PORTONE_PAYMENT_ID,
                        100_000L, "KRW", "RESERVATION_CANCELLED");
        RefundResult unknown = new RefundResult(
                "910000000000000001", PAYMENT_ID, 100_000L, 0L,
                0L, 300_000L, "KRW", RefundStatus.RECONCILIATION_REQUIRED,
                NOW.minusSeconds(10), null);
        var claim = PaymentRecoveryTransactionService.AutomaticRefundReconciliationClaim
                .lookup(refundClaim, unknown, 300_000L);
        var cancellation = new ProviderCancellation(
                "cancel-1", ProviderStatus.PARTIALLY_CANCELLED,
                100_000L, "KRW",
                PaymentProviderClient.cancellationReason(
                        "RESERVATION_CANCELLED", "910000000000000001"));
        var providerPayment = new ProviderPayment(
                PORTONE_PAYMENT_ID, "transaction-1", ProviderStatus.PARTIALLY_CANCELLED,
                300_000L, "KRW", List.of(cancellation));
        RefundResult completed = new RefundResult(
                "910000000000000001", PAYMENT_ID, 100_000L, 100_000L,
                100_000L, 200_000L, "KRW", RefundStatus.COMPLETED,
                NOW.minusSeconds(10), NOW);
        when(recoveryTransactions.claimAutomaticRefundReconciliation(query))
                .thenReturn(claim);
        when(providerClient.getPayment(PORTONE_PAYMENT_ID)).thenReturn(providerPayment);
        when(transactions.finalizeRefund(refundClaim, cancellation, NOW))
                .thenReturn(completed);

        assertThat(paymentService.reconcileRefundResult(query)).isEqualTo(completed);

        verify(providerClient, never()).cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 100_000L,
                "KRW", "RESERVATION_CANCELLED");
    }

    @Test
    @DisplayName("자동 환불 대사 timeout은 UNKNOWN을 유지하고 취소를 재전송하지 않는다")
    void keepsAutomaticRefundUnknownOnProviderTimeout() {
        ReconcileRefundResultQuery query = new ReconcileRefundResultQuery(
                PAYMENT_ID, "reservation:1:cancelled", 100_000L, "KRW");
        PaymentTransactionService.RefundClaim refundClaim =
                PaymentTransactionService.RefundClaim.requiresCall(
                        "910000000000000001", PAYMENT_ID, PORTONE_PAYMENT_ID,
                        100_000L, "KRW", "RESERVATION_CANCELLED");
        RefundResult unknown = new RefundResult(
                "910000000000000001", PAYMENT_ID, 100_000L, 0L,
                0L, 300_000L, "KRW", RefundStatus.RECONCILIATION_REQUIRED,
                NOW.minusSeconds(10), null);
        when(recoveryTransactions.claimAutomaticRefundReconciliation(query))
                .thenReturn(PaymentRecoveryTransactionService
                        .AutomaticRefundReconciliationClaim
                        .lookup(refundClaim, unknown, 300_000L));
        when(providerClient.getPayment(PORTONE_PAYMENT_ID)).thenThrow(
                new PaymentProviderClient.ProviderUnavailableException("timeout"));

        assertThat(paymentService.reconcileRefundResult(query)).isEqualTo(unknown);

        verify(transactions, never()).finalizeRefund(
                any(), any(), any());
        verify(providerClient, never()).cancelPayment(
                any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    @DisplayName("결과 불명 복구 환불의 동일 operation replay는 provider 명령을 반복하지 않는다")
    void doesNotResendUnknownManualRefundOperation() {
        RequestManualRecoveryRefundCommand command =
                new RequestManualRecoveryRefundCommand(
                        "21", 3L, 4L, 5L,
                        "550e8400-e29b-41d4-a716-446655440099");
        RequestRefundCommand canonical = new RequestRefundCommand(
                PAYMENT_ID, "reservation:1:cancelled", 100_000L,
                "RESERVATION_CANCELLED", 1L,
                "550e8400-e29b-41d4-a716-446655440000");
        PaymentTransactionService.RefundClaim refundClaim =
                PaymentTransactionService.RefundClaim.requiresCall(
                        "910000000000000001", PAYMENT_ID, PORTONE_PAYMENT_ID,
                        100_000L, "KRW", "RESERVATION_CANCELLED");
        RefundResult unknown = new RefundResult(
                "910000000000000001", PAYMENT_ID, 100_000L, 0L,
                0L, 300_000L, "KRW", RefundStatus.RECONCILIATION_REQUIRED,
                NOW.minusSeconds(10), null);
        when(recoveryTransactions.claimRefundExecution(command)).thenReturn(
                PaymentRecoveryTransactionService.ManualRefundExecutionClaim
                        .execute(canonical, null),
                PaymentRecoveryTransactionService.ManualRefundExecutionClaim
                        .replay(unknown, null));
        when(transactions.claimRefund(canonical, NOW)).thenReturn(refundClaim);
        when(providerClient.cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 100_000L,
                "KRW", "RESERVATION_CANCELLED"))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        when(transactions.markRefundUnknown(refundClaim, NOW)).thenReturn(unknown);

        assertThat(paymentService.requestManualRecoveryRefund(command)).isEqualTo(unknown);
        assertThat(paymentService.requestManualRecoveryRefund(command)).isEqualTo(unknown);

        verify(providerClient, times(1)).cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 100_000L,
                "KRW", "RESERVATION_CANCELLED");
        verify(recoveryTransactions).finishRefundExecution(
                "21", command.operationId(), unknown);
    }

    @Test
    @DisplayName("provider 호출 전 환불 선점 실패는 UNKNOWN으로 오인하지 않는다")
    void marksManualOperationFailedWhenCanonicalClaimFailsBeforeProviderCall() {
        RequestManualRecoveryRefundCommand command =
                new RequestManualRecoveryRefundCommand(
                        "21", 3L, 4L, 5L,
                        "550e8400-e29b-41d4-a716-446655440099");
        RequestRefundCommand canonical = new RequestRefundCommand(
                PAYMENT_ID, "reservation:1:cancelled", 100_000L,
                "RESERVATION_CANCELLED", 1L,
                "550e8400-e29b-41d4-a716-446655440000");
        when(recoveryTransactions.claimRefundExecution(command)).thenReturn(
                PaymentRecoveryTransactionService.ManualRefundExecutionClaim
                        .execute(canonical, null));
        when(transactions.claimRefund(canonical, NOW)).thenThrow(
                new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION));

        assertThatThrownBy(() -> paymentService.requestManualRecoveryRefund(command))
                .isInstanceOf(ServiceException.class);

        verify(recoveryTransactions).markRefundExecutionFailed(
                "21", command.operationId());
        verify(recoveryTransactions, never()).markRefundExecutionUnknown(
                "21", command.operationId());
        verify(providerClient, never()).cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 100_000L,
                "KRW", "RESERVATION_CANCELLED");
    }

    @Test
    @DisplayName("수동 복구 재조회 timeout은 상태를 유지하고 외부 명령을 보내지 않는다")
    void keepsUnknownStateWhenManualRequeryTimesOut() {
        ReconcileManualRecoveryCommand command = new ReconcileManualRecoveryCommand(
                "21", 3L, 4L, 5L);
        PaymentTransactionService.RefundClaim refundClaim =
                PaymentTransactionService.RefundClaim.requiresCall(
                        "910000000000000001", PAYMENT_ID, PORTONE_PAYMENT_ID,
                        100_000L, "KRW", "RESERVATION_CANCELLED");
        var claim = PaymentRecoveryTransactionService.ManualReconciliationClaim
                .refund("21", refundClaim, 300_000L);
        ManualRecoveryInspection expected = mock(ManualRecoveryInspection.class);
        when(recoveryTransactions.claimReconciliation(command)).thenReturn(claim);
        when(providerClient.getPayment(PORTONE_PAYMENT_ID)).thenThrow(
                new PaymentProviderClient.ProviderUnavailableException("timeout"));
        when(recoveryTransactions.inspect(new InspectManualRecoveryQuery("21")))
                .thenReturn(expected);

        assertThat(paymentService.reconcileManualRecovery(command)).isSameAs(expected);

        verify(transactions, never()).finalizeRefund(
                any(PaymentTransactionService.RefundClaim.class),
                any(ProviderCancellation.class), any(Instant.class));
        verify(providerClient, never()).cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 100_000L,
                "KRW", "RESERVATION_CANCELLED");
    }

    @Test
    void returnsStoredReservationDepositSnapshotWithoutProviderAccess() {
        StoreReservationPaymentSnapshot expected = new StoreReservationPaymentSnapshot(
                PAYMENT_ID,
                30_000L,
                10_000L,
                20_000L,
                "KRW",
                PaymentStatus.PARTIALLY_REFUNDED,
                PaymentAttemptStatus.PAID,
                NOW.minusSeconds(120),
                NOW.minusSeconds(90),
                NOW,
                List.of(new StoreReservationRefundSnapshot(
                        "910000000000000001",
                        10_000L,
                        RefundStatus.COMPLETED,
                        NOW.minusSeconds(30),
                        NOW))
        );
        when(transactions.findReservationDepositPayment(PAYMENT_ID, SOURCE_REFERENCE_ID))
                .thenReturn(Optional.of(expected));

        assertThat(paymentService.findReservationDepositPayment(PAYMENT_ID, SOURCE_REFERENCE_ID))
                .contains(expected);
        verify(transactions).findReservationDepositPayment(PAYMENT_ID, SOURCE_REFERENCE_ID);
        verify(providerClient, never()).getPayment(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reservationDepositSnapshotIsMinimalAndImmutable() {
        List<StoreReservationRefundSnapshot> mutableRefunds = new java.util.ArrayList<>();
        StoreReservationPaymentSnapshot snapshot = new StoreReservationPaymentSnapshot(
                PAYMENT_ID,
                30_000L,
                0L,
                30_000L,
                "KRW",
                PaymentStatus.PAID,
                PaymentAttemptStatus.PAID,
                NOW.minusSeconds(120),
                NOW.minusSeconds(90),
                NOW,
                mutableRefunds
        );

        mutableRefunds.add(new StoreReservationRefundSnapshot(
                "910000000000000001", 10_000L, RefundStatus.COMPLETED, NOW, NOW));

        assertThat(snapshot.refunds()).isEmpty();
        assertThatThrownBy(() -> snapshot.refunds().add(new StoreReservationRefundSnapshot(
                "910000000000000002", 10_000L, RefundStatus.PROCESSING, NOW, null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(recordComponentNames(StoreReservationPaymentSnapshot.class)).containsExactly(
                "paymentId", "amountMinor", "refundedAmountMinor", "refundableAmountMinor",
                "currency", "status", "lastAttemptStatus", "createdAt", "paidAt",
                "updatedAt", "refunds");
        assertThat(recordComponentNames(StoreReservationRefundSnapshot.class)).containsExactly(
                "refundId", "amountMinor", "status", "requestedAt", "completedAt");
    }

    @Test
    void transactionSnapshotFiltersSourceAndOrdersRefundsDeterministically() {
        Payment payment = org.mockito.Mockito.mock(Payment.class);
        PaymentRefund later = org.mockito.Mockito.mock(PaymentRefund.class);
        PaymentRefund firstByIdAtSameTime = org.mockito.Mockito.mock(PaymentRefund.class);
        PaymentRefund secondByIdAtSameTime = org.mockito.Mockito.mock(PaymentRefund.class);
        when(payments.findByPaymentIdAndSourceTypeAndSourceReferenceId(
                PAYMENT_ID, "RESERVATION_DEPOSIT", SOURCE_REFERENCE_ID))
                .thenReturn(Optional.of(payment));
        when(payment.getId()).thenReturn(77L);
        when(payment.getPaymentId()).thenReturn(PAYMENT_ID);
        when(payment.getAmountMinor()).thenReturn(30_000L);
        when(payment.getRefundedAmountMinor()).thenReturn(10_000L);
        when(payment.getRefundableAmountMinor()).thenReturn(20_000L);
        when(payment.getCurrency()).thenReturn("KRW");
        when(payment.getStatus()).thenReturn(Payment.Status.PARTIALLY_REFUNDED);
        when(payment.getLastAttemptStatus()).thenReturn(Payment.AttemptStatus.PAID);
        when(payment.getCreatedAt()).thenReturn(NOW.minusSeconds(120));
        when(payment.getPaidAt()).thenReturn(NOW.minusSeconds(90));
        when(payment.getUpdatedAt()).thenReturn(NOW);
        stubRefund(later, "910000000000000003", NOW.minusSeconds(10));
        stubRefund(secondByIdAtSameTime, "910000000000000002", NOW.minusSeconds(20));
        stubRefund(firstByIdAtSameTime, "910000000000000001", NOW.minusSeconds(20));
        when(refunds.findByPayment_IdOrderByRequestedAtAsc(77L))
                .thenReturn(List.of(later, secondByIdAtSameTime, firstByIdAtSameTime));
        PaymentTransactionService queryTransactions = new PaymentTransactionService(
                payments, null, refunds, null, null, null, null, null);

        Optional<StoreReservationPaymentSnapshot> result =
                queryTransactions.findReservationDepositPayment(PAYMENT_ID, SOURCE_REFERENCE_ID);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(result.orElseThrow().lastAttemptStatus()).isEqualTo(PaymentAttemptStatus.PAID);
        assertThat(result.orElseThrow().refunds())
                .extracting(StoreReservationRefundSnapshot::refundId)
                .containsExactly(
                        "910000000000000001",
                        "910000000000000002",
                        "910000000000000003");
    }

    @Test
    void transactionSnapshotReturnsAbsentForMissingOrWrongSourcePayment() {
        when(payments.findByPaymentIdAndSourceTypeAndSourceReferenceId(
                PAYMENT_ID, "RESERVATION_DEPOSIT", SOURCE_REFERENCE_ID))
                .thenReturn(Optional.empty());
        PaymentTransactionService queryTransactions = new PaymentTransactionService(
                payments, null, refunds, null, null, null, null, null);

        assertThat(queryTransactions.findReservationDepositPayment(
                PAYMENT_ID, SOURCE_REFERENCE_ID)).isEmpty();
        verify(refunds, never()).findByPayment_IdOrderByRequestedAtAsc(
                org.mockito.ArgumentMatchers.anyLong());
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
                12L,
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
                        12L,
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
    @DisplayName("0퍼센트 예약금 처분은 환불이나 provider 호출 없이 저장 결과를 반환한다")
    void appliesZeroPercentDispositionWithoutRefund() {
        ApplyReservationDepositDispositionCommand command = dispositionCommand(0);
        DispositionResult expected = dispositionResult(
                0, 0L, 0L, null, DispositionStatus.COMPLETED, null);
        PaymentTransactionService.DispositionClaim claim =
                PaymentTransactionService.DispositionClaim.completed(expected);
        when(transactions.claimDisposition(command, NOW)).thenReturn(claim);

        DispositionResult result = paymentService.applyReservationDepositDisposition(command);

        assertThat(result).isEqualTo(expected);
        verify(providerClient, never()).cancelPayment(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("50퍼센트 예약금 처분은 기존 환불 machinery 결과를 처분 원장에 반영한다")
    void appliesPositiveDispositionThroughExistingRefundMachinery() {
        ApplyReservationDepositDispositionCommand command = dispositionCommand(5000);
        RequestRefundCommand refundCommand = new RequestRefundCommand(
                PAYMENT_ID,
                command.sourceEventId(),
                15_000L,
                "RESERVATION_DEPOSIT_DISPOSITION",
                2L,
                command.idempotencyKey());
        PaymentTransactionService.DispositionClaim dispositionClaim =
                PaymentTransactionService.DispositionClaim.requiresRefund(
                        command.idempotencyKey(), refundCommand);
        PaymentTransactionService.RefundClaim refundClaim =
                PaymentTransactionService.RefundClaim.requiresCall(
                        "910000000000000001", PAYMENT_ID, PORTONE_PAYMENT_ID,
                        15_000L, "KRW", "RESERVATION_DEPOSIT_DISPOSITION");
        ProviderCancellation providerCancellation = new ProviderCancellation(
                "cancellation-1", ProviderStatus.PARTIALLY_CANCELLED, 15_000L, "KRW");
        RefundResult refundResult = new RefundResult(
                "910000000000000001", PAYMENT_ID, 15_000L, 15_000L,
                15_000L, 15_000L, "KRW", RefundStatus.COMPLETED,
                NOW, NOW);
        DispositionResult expected = dispositionResult(
                5000, 15_000L, 15_000L, "910000000000000001",
                DispositionStatus.COMPLETED, null);
        when(transactions.claimDisposition(command, NOW)).thenReturn(dispositionClaim);
        when(transactions.claimRefund(refundCommand, NOW)).thenReturn(refundClaim);
        when(providerClient.cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 15_000L, "KRW",
                "RESERVATION_DEPOSIT_DISPOSITION")).thenReturn(providerCancellation);
        when(transactions.finalizeRefund(refundClaim, providerCancellation, NOW))
                .thenReturn(refundResult);
        when(transactions.finalizeDisposition(
                command.idempotencyKey(), refundResult, NOW)).thenReturn(expected);

        DispositionResult result = paymentService.applyReservationDepositDisposition(command);

        assertThat(result).isEqualTo(expected);
        verify(transactions).finalizeDisposition(command.idempotencyKey(), refundResult, NOW);
    }

    @Test
    @DisplayName("provider 결과가 불명확한 예약금 처분은 대사 필요·UNKNOWN으로 수렴한다")
    void marksDispositionUnknownWhenRefundOutcomeIsUnknown() {
        ApplyReservationDepositDispositionCommand command = dispositionCommand(5000);
        RequestRefundCommand refundCommand = dispositionRefundCommand(command);
        PaymentTransactionService.DispositionClaim dispositionClaim =
                PaymentTransactionService.DispositionClaim.requiresRefund(
                        command.idempotencyKey(), refundCommand);
        PaymentTransactionService.RefundClaim refundClaim =
                PaymentTransactionService.RefundClaim.requiresCall(
                        "910000000000000001", PAYMENT_ID, PORTONE_PAYMENT_ID,
                        15_000L, "KRW", "RESERVATION_DEPOSIT_DISPOSITION");
        RefundResult refundResult = refundResult(
                RefundStatus.RECONCILIATION_REQUIRED, 0L, null);
        DispositionResult expected = dispositionResult(
                5000, 15_000L, 0L, "910000000000000001",
                DispositionStatus.RECONCILIATION_REQUIRED,
                DispositionFailureClassification.UNKNOWN);
        when(transactions.claimDisposition(command, NOW)).thenReturn(dispositionClaim);
        when(transactions.claimRefund(refundCommand, NOW)).thenReturn(refundClaim);
        when(providerClient.cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 15_000L, "KRW",
                "RESERVATION_DEPOSIT_DISPOSITION"))
                .thenThrow(new PaymentProviderClient.ProviderUnavailableException("timeout"));
        when(transactions.markRefundUnknown(refundClaim, NOW)).thenReturn(refundResult);
        when(transactions.finalizeDisposition(
                command.idempotencyKey(), refundResult, NOW)).thenReturn(expected);

        DispositionResult result = paymentService.applyReservationDepositDisposition(command);

        assertThat(result.status()).isEqualTo(DispositionStatus.RECONCILIATION_REQUIRED);
        assertThat(result.failureClassification())
                .isEqualTo(DispositionFailureClassification.UNKNOWN);
    }

    @Test
    @DisplayName("provider 명시 실패는 같은 환불을 재시도할 수 있는 처분 실패로 수렴한다")
    void marksDispositionRetryableWhenProviderExplicitlyFails() {
        ApplyReservationDepositDispositionCommand command = dispositionCommand(5000);
        RequestRefundCommand refundCommand = dispositionRefundCommand(command);
        PaymentTransactionService.DispositionClaim dispositionClaim =
                PaymentTransactionService.DispositionClaim.requiresRefund(
                        command.idempotencyKey(), refundCommand);
        PaymentTransactionService.RefundClaim refundClaim =
                PaymentTransactionService.RefundClaim.requiresCall(
                        "910000000000000001", PAYMENT_ID, PORTONE_PAYMENT_ID,
                        15_000L, "KRW", "RESERVATION_DEPOSIT_DISPOSITION");
        ProviderCancellation providerCancellation = new ProviderCancellation(
                "cancellation-failed", ProviderStatus.FAILED, 15_000L, "KRW");
        RefundResult refundResult = refundResult(RefundStatus.FAILED, 0L, null);
        DispositionResult expected = dispositionResult(
                5000, 15_000L, 0L, "910000000000000001",
                DispositionStatus.FAILED, DispositionFailureClassification.RETRYABLE);
        when(transactions.claimDisposition(command, NOW)).thenReturn(dispositionClaim);
        when(transactions.claimRefund(refundCommand, NOW)).thenReturn(refundClaim);
        when(providerClient.cancelPayment(
                PORTONE_PAYMENT_ID, "910000000000000001", 15_000L, "KRW",
                "RESERVATION_DEPOSIT_DISPOSITION")).thenReturn(providerCancellation);
        when(transactions.finalizeRefund(refundClaim, providerCancellation, NOW))
                .thenReturn(refundResult);
        when(transactions.finalizeDisposition(
                command.idempotencyKey(), refundResult, NOW)).thenReturn(expected);

        DispositionResult result = paymentService.applyReservationDepositDisposition(command);

        assertThat(result.failureClassification())
                .isEqualTo(DispositionFailureClassification.RETRYABLE);
    }

    @Test
    @DisplayName("환불 계약의 결정적 거부는 처분을 영구 실패로 보존해 반환한다")
    void persistsPermanentDispositionWhenRefundContractRejects() {
        ApplyReservationDepositDispositionCommand command = dispositionCommand(5000);
        RequestRefundCommand refundCommand = dispositionRefundCommand(command);
        PaymentTransactionService.DispositionClaim dispositionClaim =
                PaymentTransactionService.DispositionClaim.requiresRefund(
                        command.idempotencyKey(), refundCommand);
        DispositionResult expected = dispositionResult(
                5000, 15_000L, 0L, null,
                DispositionStatus.FAILED, DispositionFailureClassification.PERMANENT);
        when(transactions.claimDisposition(command, NOW)).thenReturn(dispositionClaim);
        when(transactions.claimRefund(refundCommand, NOW))
                .thenThrow(new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION));
        when(transactions.resolveDispositionAfterRefundFailure(
                command.idempotencyKey(), false, NOW)).thenReturn(expected);

        DispositionResult result = paymentService.applyReservationDepositDisposition(command);

        assertThat(result).isEqualTo(expected);
        verify(transactions).resolveDispositionAfterRefundFailure(
                command.idempotencyKey(), false, NOW);
    }

    @Test
    @DisplayName("sibling PROCESSING 잔액 충돌은 refund 없는 retryable 처분으로 보존한다")
    void preservesRetryableDispositionWhenRefundCapacityIsTemporarilyReserved() {
        ApplyReservationDepositDispositionCommand command = dispositionCommand(5000);
        RequestRefundCommand refundCommand = dispositionRefundCommand(command);
        PaymentTransactionService.DispositionClaim dispositionClaim =
                PaymentTransactionService.DispositionClaim.requiresRefund(
                        command.idempotencyKey(), refundCommand);
        DispositionResult expected = dispositionResult(
                5000, 15_000L, 0L, null,
                DispositionStatus.FAILED, DispositionFailureClassification.RETRYABLE);
        when(transactions.claimDisposition(command, NOW)).thenReturn(dispositionClaim);
        when(transactions.claimRefund(refundCommand, NOW))
                .thenThrow(new ServiceException(PaymentErrorCode.REFUND_AMOUNT_EXCEEDED));
        when(transactions.resolveDispositionAfterRefundFailure(
                command.idempotencyKey(), true, NOW)).thenReturn(expected);

        DispositionResult result = paymentService.applyReservationDepositDisposition(command);

        assertThat(result).isEqualTo(expected);
        verify(transactions).resolveDispositionAfterRefundFailure(
                command.idempotencyKey(), true, NOW);
        verify(providerClient, never()).cancelPayment(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("예약금 처분 조회는 저장 결과만 반환하고 provider를 호출하지 않는다")
    void getsDispositionWithoutProviderCall() {
        GetReservationDepositDispositionQuery query =
                new GetReservationDepositDispositionQuery(
                        PAYMENT_ID, "reservation:123:cancelled");
        DispositionResult expected = dispositionResult(
                5000, 15_000L, 15_000L, "910000000000000001",
                DispositionStatus.COMPLETED, null);
        when(transactions.claimDispositionReconciliation(query, NOW))
                .thenReturn(PaymentTransactionService.DispositionReconciliationClaim
                        .completed(expected));

        DispositionResult result = paymentService.getReservationDepositDisposition(query);

        assertThat(result).isEqualTo(expected);
        verify(providerClient, never()).cancelPayment(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("UNKNOWN 처분 조회는 marker가 일치한 provider 취소만 GET으로 대사 완료한다")
    void reconcilesUnknownDispositionFromMatchingProviderCancellation() {
        GetReservationDepositDispositionQuery query =
                new GetReservationDepositDispositionQuery(
                        PAYMENT_ID, "reservation:123:cancelled");
        DispositionResult unknown = dispositionResult(
                5000, 15_000L, 0L, "910000000000000001",
                DispositionStatus.RECONCILIATION_REQUIRED,
                DispositionFailureClassification.UNKNOWN);
        PaymentTransactionService.DispositionReconciliationClaim claim =
                PaymentTransactionService.DispositionReconciliationClaim.requiresLookup(
                        unknown.dispositionId(),
                        PAYMENT_ID,
                        PORTONE_PAYMENT_ID,
                        "910000000000000001",
                        30_000L,
                        15_000L,
                        "KRW",
                        "RESERVATION_DEPOSIT_DISPOSITION",
                        unknown);
        ProviderCancellation cancellation = new ProviderCancellation(
                "cancellation-reconciled",
                ProviderStatus.PARTIALLY_CANCELLED,
                15_000L,
                "KRW",
                "RESERVATION_DEPOSIT_DISPOSITION "
                        + "[MIRIYUM_REFUND_ID=910000000000000001]");
        ProviderPayment providerPayment = new ProviderPayment(
                PORTONE_PAYMENT_ID,
                "transaction-1",
                ProviderStatus.PARTIALLY_CANCELLED,
                30_000L,
                "KRW",
                List.of(cancellation));
        DispositionResult completed = dispositionResult(
                5000, 15_000L, 15_000L, "910000000000000001",
                DispositionStatus.COMPLETED, null);
        when(transactions.claimDispositionReconciliation(query, NOW)).thenReturn(claim);
        when(providerClient.getPayment(PORTONE_PAYMENT_ID)).thenReturn(providerPayment);
        when(transactions.finalizeDispositionReconciliation(claim, cancellation, NOW))
                .thenReturn(completed);

        DispositionResult result = paymentService.getReservationDepositDisposition(query);

        assertThat(result).isEqualTo(completed);
        verify(providerClient, never()).cancelPayment(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("같은 marker 취소가 둘이면 어느 것도 추정하지 않고 UNKNOWN을 유지한다")
    void keepsUnknownDispositionWhenProviderMarkerIsAmbiguous() {
        GetReservationDepositDispositionQuery query =
                new GetReservationDepositDispositionQuery(
                        PAYMENT_ID, "reservation:123:cancelled");
        DispositionResult unknown = dispositionResult(
                5000, 15_000L, 0L, "910000000000000001",
                DispositionStatus.RECONCILIATION_REQUIRED,
                DispositionFailureClassification.UNKNOWN);
        PaymentTransactionService.DispositionReconciliationClaim claim =
                PaymentTransactionService.DispositionReconciliationClaim.requiresLookup(
                        unknown.dispositionId(), PAYMENT_ID, PORTONE_PAYMENT_ID,
                        "910000000000000001", 30_000L, 15_000L, "KRW",
                        "RESERVATION_DEPOSIT_DISPOSITION", unknown);
        String reason = PaymentProviderClient.cancellationReason(
                "RESERVATION_DEPOSIT_DISPOSITION", "910000000000000001");
        ProviderPayment providerPayment = new ProviderPayment(
                PORTONE_PAYMENT_ID,
                "transaction-1",
                ProviderStatus.PARTIALLY_CANCELLED,
                30_000L,
                "KRW",
                List.of(
                        new ProviderCancellation(
                                "cancellation-1", ProviderStatus.PARTIALLY_CANCELLED,
                                15_000L, "KRW", reason),
                        new ProviderCancellation(
                                "cancellation-2", ProviderStatus.PARTIALLY_CANCELLED,
                                15_000L, "KRW", reason)));
        when(transactions.claimDispositionReconciliation(query, NOW)).thenReturn(claim);
        when(providerClient.getPayment(PORTONE_PAYMENT_ID)).thenReturn(providerPayment);

        DispositionResult result = paymentService.getReservationDepositDisposition(query);

        assertThat(result).isEqualTo(unknown);
        verify(transactions, never()).finalizeDispositionReconciliation(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(providerClient, never()).cancelPayment(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Reservation 공개 준비·환불 DTO는 Payment 정본의 scalar 필드만 정확히 노출한다")
    void exposesCanonicalReservationPaymentContracts() {
        assertThat(recordComponentNames(PrepareReservationDepositCommand.class)).containsExactly(
                "sourceReferenceId", "caseType", "storeId", "consumerAccountId", "amountMinor", "currency",
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
        assertThat(recordComponentNames(ApplyReservationDepositDispositionCommand.class))
                .containsExactly(
                        "paymentId", "sourceEventId", "sourceEventType",
                        "correctsSourceEventId", "policyVersion", "responsibilityCode",
                        "targetRefundRateBasisPoints", "idempotencyKey");
        assertThat(recordComponentNames(GetReservationDepositDispositionQuery.class))
                .containsExactly("paymentId", "sourceEventId");
        assertThat(recordComponentNames(DispositionResult.class)).containsExactly(
                "dispositionId", "paymentId", "sourceEventId", "sourceEventType",
                "correctsSourceEventId", "policyVersion", "responsibilityCode",
                "targetRefundRateBasisPoints", "originalAmountMinor",
                "targetRefundAmountMinor", "incrementalRefundAmountMinor",
                "completedRefundAmountMinor", "withheldAmountMinor", "currency",
                "refundId", "status", "failureClassification", "requestedAt",
                "updatedAt", "completedAt");
    }

    @Test
    @DisplayName("예약금 처분 명령은 승인된 목표율과 정규화 UUID만 허용한다")
    void validatesReservationDepositDispositionCommand() {
        assertThatThrownBy(() -> new ApplyReservationDepositDispositionCommand(
                PAYMENT_ID,
                "reservation:123:cancelled",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                2500,
                "550e8400-e29b-41d4-a716-446655440001"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ApplyReservationDepositDispositionCommand(
                PAYMENT_ID,
                "reservation:123:cancelled",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5000,
                "550E8400-E29B-41D4-A716-446655440001"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .toList();
    }

    private static void stubRefund(PaymentRefund refund, String refundId, Instant requestedAt) {
        when(refund.getRefundId()).thenReturn(refundId);
        when(refund.getAmountMinor()).thenReturn(5_000L);
        when(refund.getStatus()).thenReturn(RefundStatus.COMPLETED);
        when(refund.getRequestedAt()).thenReturn(requestedAt);
        when(refund.getCompletedAt()).thenReturn(requestedAt.plusSeconds(1));
    }

    private static PrepareReservationDepositCommand prepareCommand() {
        return new PrepareReservationDepositCommand(
                "123",
                12L,
                11L,
                30_000L,
                "KRW",
                NOW.plusSeconds(600),
                7L,
                "550e8400-e29b-41d4-a716-446655440123"
        );
    }

    private static ApplyReservationDepositDispositionCommand dispositionCommand(int targetRate) {
        return new ApplyReservationDepositDispositionCommand(
                PAYMENT_ID,
                "reservation:123:cancelled",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                targetRate,
                "550e8400-e29b-41d4-a716-446655440001"
        );
    }

    private static RequestRefundCommand dispositionRefundCommand(
            ApplyReservationDepositDispositionCommand command
    ) {
        return new RequestRefundCommand(
                PAYMENT_ID,
                command.sourceEventId(),
                15_000L,
                "RESERVATION_DEPOSIT_DISPOSITION",
                command.policyVersion(),
                command.idempotencyKey());
    }

    private static RefundResult refundResult(
            RefundStatus status,
            long completedAmount,
            Instant completedAt
    ) {
        return new RefundResult(
                "910000000000000001",
                PAYMENT_ID,
                15_000L,
                completedAmount,
                completedAmount,
                30_000L - completedAmount,
                "KRW",
                status,
                NOW,
                completedAt);
    }

    private static DispositionResult dispositionResult(
            int targetRate,
            long targetAmount,
            long completedAmount,
            String refundId,
            DispositionStatus status,
            DispositionFailureClassification failureClassification
    ) {
        return new DispositionResult(
                "550e8400-e29b-41d4-a716-446655440001",
                PAYMENT_ID,
                "reservation:123:cancelled",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                targetRate,
                30_000L,
                targetAmount,
                targetAmount,
                completedAmount,
                30_000L - targetAmount,
                "KRW",
                refundId,
                status,
                failureClassification,
                NOW,
                NOW,
                status == DispositionStatus.COMPLETED ? NOW : null
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
                12L,
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
