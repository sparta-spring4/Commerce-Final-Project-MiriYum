package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RequestManualRecoveryRefundCommand;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentRecoveryExecutionJobTest {
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
    @Mock PaymentRecoveryExecutionTransaction transactions;
    @Mock PaymentService payments;
    private PaymentRecoveryExecutionJob job;

    @BeforeEach
    void setUp() {
        job = new PaymentRecoveryExecutionJob(transactions, payments, "worker-281");
    }

    @Test
    void unknownRefundChangesDurableOperationToLookupOnly() {
        var claim = claim(RecoveryAction.RETRY_REFUND);
        when(transactions.claim("worker-281")).thenReturn(Optional.of(claim));
        RefundResult unknown = new RefundResult("1", "2", 100_000L, 0L, 0L,
                300_000L, "KRW", RefundStatus.RECONCILIATION_REQUIRED, NOW, null);
        when(payments.requestManualRecoveryRefund(new RequestManualRecoveryRefundCommand(
                "281", 3L, 4L, 5L, claim.operationId()))).thenReturn(unknown);

        job.runOnce();

        verify(transactions).scheduleLookup(claim, "RESULT_UNKNOWN");
        verify(payments, never()).reconcileManualRecovery(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reclaimedLookupNeverCallsRefundAgain() {
        var claim = claim(RecoveryAction.REQUERY_PROVIDER_RESULT);
        when(transactions.claim("worker-281")).thenReturn(Optional.of(claim));
        ManualRecoveryInspection inspection = new ManualRecoveryInspection(
                "281", 3L, 4L, 5L,
                com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN,
                300_000L, 100_000L, 200_000L, "KRW", ManualRecoveryResultStatus.UNKNOWN,
                Set.of(com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryAction.REQUERY_PROVIDER_RESULT),
                "port********abc");
        when(payments.inspectManualRecovery(org.mockito.ArgumentMatchers.any())).thenReturn(inspection);
        when(payments.reconcileManualRecovery(org.mockito.ArgumentMatchers.any())).thenReturn(inspection);

        job.runOnce();

        verify(transactions).recordInspection(claim, inspection);
        verify(payments, never()).requestManualRecoveryRefund(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void alreadyAchievedResultCompletesFromInspectionWithoutAnotherProviderLookup() {
        var claim = claim(RecoveryAction.REQUERY_PROVIDER_RESULT);
        when(transactions.claim("worker-281")).thenReturn(Optional.of(claim));
        ManualRecoveryInspection succeeded = new ManualRecoveryInspection(
                "281", 8L, 9L, 10L,
                com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN,
                300_000L, 300_000L, 0L, "KRW", ManualRecoveryResultStatus.SUCCEEDED,
                Set.of(), "port********abc");
        when(payments.inspectManualRecovery(org.mockito.ArgumentMatchers.any())).thenReturn(succeeded);

        job.runOnce();

        verify(transactions).recordInspection(claim, succeeded);
        verify(payments, never()).reconcileManualRecovery(org.mockito.ArgumentMatchers.any());
        verify(payments, never()).requestManualRecoveryRefund(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void explicitProviderFailureBecomesTerminalAndIsNotAutomaticallyRetried() {
        var claim = claim(RecoveryAction.RETRY_REFUND);
        when(transactions.claim("worker-281")).thenReturn(Optional.of(claim));
        RefundResult failed = new RefundResult("1", "2", 100_000L, 0L, 0L,
                300_000L, "KRW", RefundStatus.FAILED, NOW, null);
        when(payments.requestManualRecoveryRefund(org.mockito.ArgumentMatchers.any())).thenReturn(failed);

        job.runOnce();

        verify(transactions).recordProviderFailure(claim);
        verify(transactions, never()).scheduleLookup(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void localCommitFailureIsNotMisclassifiedAsUnknownProviderResult() {
        var claim = claim(RecoveryAction.REQUERY_PROVIDER_RESULT);
        when(transactions.claim("worker-281")).thenReturn(Optional.of(claim));
        ManualRecoveryInspection succeeded = new ManualRecoveryInspection(
                "281", 8L, 9L, 10L,
                com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN,
                300_000L, 300_000L, 0L, "KRW", ManualRecoveryResultStatus.SUCCEEDED,
                Set.of(), "port********abc");
        when(payments.inspectManualRecovery(org.mockito.ArgumentMatchers.any())).thenReturn(succeeded);
        doThrow(new IllegalStateException("local commit failed"))
                .when(transactions).recordInspection(claim, succeeded);

        assertThatThrownBy(job::runOnce).isInstanceOf(IllegalStateException.class);

        verify(transactions, never()).recordFailure(claim);
    }

    private static PaymentRecoveryExecutionTransaction.Claim claim(RecoveryAction operation) {
        return new PaymentRecoveryExecutionTransaction.Claim(
                1L, "550e8400-e29b-41d4-a716-446655440281", "281", operation,
                "550e8400-e29b-41d4-a716-446655440282", 3L, 4L, 5L,
                "worker-281", 7L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE));
    }
}
