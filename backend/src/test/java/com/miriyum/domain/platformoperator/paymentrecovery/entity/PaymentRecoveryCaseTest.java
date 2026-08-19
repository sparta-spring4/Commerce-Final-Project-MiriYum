package com.miriyum.domain.platformoperator.paymentrecovery.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.exception.PaymentRecoveryErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentRecoveryCaseTest {
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Test
    void ordinaryRefundMovesThroughProposalExecutionVerificationAndCompletion() {
        PaymentRecoveryCase recovery = unknownRefund();

        recovery.beginInvestigation(1L, NOW.plusSeconds(1));
        recovery.recordProposal(2L, 1L, ApprovalTier.SINGLE_OPERATOR, NOW.plusSeconds(2));
        recovery.queueExecution(3L, NOW.plusSeconds(3));
        recovery.startVerification(4L, NOW.plusSeconds(4));
        recovery.complete(5L, NOW.plusSeconds(5));

        assertThat(recovery.getStatus()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(recovery.getCaseVersion()).isEqualTo(6L);
        assertThat(recovery.getCurrentProposalVersion()).isEqualTo(1L);
        assertThat(recovery.getActiveLineageKey()).isNull();
    }

    @Test
    void highValueRefundCannotExecuteBeforeAdditionalApproval() {
        PaymentRecoveryCase recovery = unknownRefund();
        recovery.beginInvestigation(1L, NOW.plusSeconds(1));
        recovery.recordProposal(2L, 1L, ApprovalTier.ADDITIONAL_SUPER_ADMIN, NOW.plusSeconds(2));

        assertThat(recovery.getStatus()).isEqualTo(CaseStatus.ADDITIONAL_APPROVAL_PENDING);
        assertThatThrownBy(() -> recovery.queueExecution(3L, NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT));

        recovery.recordAdditionalApproval(3L, NOW.plusSeconds(3));
        recovery.queueExecution(4L, NOW.plusSeconds(4));

        assertThat(recovery.getStatus()).isEqualTo(CaseStatus.EXECUTING);
    }

    @Test
    void staleVersionCannotMutateCase() {
        PaymentRecoveryCase recovery = unknownRefund();

        assertThatThrownBy(() -> recovery.beginInvestigation(2L, NOW.plusSeconds(1)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT));

        assertThat(recovery.getStatus()).isEqualTo(CaseStatus.RECONCILIATION_PENDING);
        assertThat(recovery.getCaseVersion()).isEqualTo(1L);
    }

    @Test
    void holdMayResumeOrCloseUnresolvedButTerminalCaseNeverReopens() {
        PaymentRecoveryCase recovery = unknownRefund();
        recovery.beginInvestigation(1L, NOW.plusSeconds(1));
        recovery.recordProposal(2L, 1L, ApprovalTier.SINGLE_OPERATOR, NOW.plusSeconds(2));
        recovery.queueExecution(3L, NOW.plusSeconds(3));
        recovery.startVerification(4L, NOW.plusSeconds(4));
        recovery.hold(5L, NOW.plusSeconds(5));
        recovery.resumeInvestigation(6L, NOW.plusSeconds(6));
        recovery.holdFromInvestigation(7L, NOW.plusSeconds(7));
        recovery.closeUnresolved(8L, NOW.plusSeconds(8));

        assertThat(recovery.getStatus()).isEqualTo(CaseStatus.FAILED_UNRESOLVED);
        assertThat(recovery.getActiveLineageKey()).isNull();
        assertThatThrownBy(() -> recovery.resumeInvestigation(9L, NOW.plusSeconds(9)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT));
    }

    private static PaymentRecoveryCase unknownRefund() {
        return PaymentRecoveryCase.open(
                "21",
                RecoveryKind.REFUND_RESULT_UNKNOWN,
                ResultStatus.UNKNOWN,
                300_000L,
                100_000L,
                200_000L,
                "KRW",
                Set.of(RecoveryAction.REQUERY_PROVIDER_RESULT, RecoveryAction.RETRY_REFUND),
                "port********0001",
                3L,
                4L,
                5L,
                NOW);
    }
}
