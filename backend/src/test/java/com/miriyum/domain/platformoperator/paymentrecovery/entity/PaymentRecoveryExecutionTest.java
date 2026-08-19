package com.miriyum.domain.platformoperator.paymentrecovery.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ExecutionStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.exception.PaymentRecoveryErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentRecoveryExecutionTest {
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Test
    void unknownRefundPermanentlyChangesTheExecutionToLookupOnly() {
        PaymentRecoveryExecution execution = execution();
        long token = execution.claim("worker-a", NOW, NOW.plusSeconds(30));

        execution.markUnknown("worker-a", token, NOW.plusSeconds(1), NOW.plusSeconds(10));
        long reclaimed = execution.claim("worker-b", NOW.plusSeconds(31), NOW.plusSeconds(61));

        assertThat(execution.getOperation()).isEqualTo(RecoveryAction.REQUERY_PROVIDER_RESULT);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.PROCESSING);
        assertThat(execution.getLeaseOwner()).isEqualTo("worker-b");
        assertThat(reclaimed).isGreaterThan(token);
    }

    @Test
    void staleLeaseTokenCannotCompleteExecution() {
        PaymentRecoveryExecution execution = execution();
        long token = execution.claim("worker-a", NOW, NOW.plusSeconds(30));

        assertThatThrownBy(() -> execution.markSucceeded(
                "worker-a", token + 1L, "SUCCEEDED", NOW.plusSeconds(1)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.PROCESSING);
    }

    @Test
    void requeryAuthorizationHasNoProposalAndCanNeverBecomeRefundOperation() {
        var recoveryCase = PaymentRecoveryCase.open("281",
                PaymentRecoveryEnums.RecoveryKind.REFUND_RESULT_UNKNOWN,
                PaymentRecoveryEnums.ResultStatus.UNKNOWN, 300_000L, 0L, 300_000L,
                "KRW", Set.of(RecoveryAction.REQUERY_PROVIDER_RESULT), "port********abc",
                3L, 4L, 5L, NOW);
        recoveryCase.beginInvestigation(1L, NOW);
        PaymentRecoveryExecution execution = PaymentRecoveryExecution.authorizeRequery(
                recoveryCase, 11L, 7L, NOW);

        assertThat(execution.getProposalVersion()).isNull();
        assertThat(execution.getOperation()).isEqualTo(RecoveryAction.REQUERY_PROVIDER_RESULT);
        assertThat(execution.getAuthorizedCaseVersion()).isEqualTo(2L);
    }

    private static PaymentRecoveryExecution execution() {
        PaymentRecoveryProposal proposal = PaymentRecoveryProposal.propose(
                "550e8400-e29b-41d4-a716-446655440281", 1L, 3L,
                RecoveryAction.RETRY_REFUND, 100_000L, 100_000L, 300_000L, "KRW",
                3L, 4L, 5L, "a".repeat(64), 11L, 7L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                "550e8400-e29b-41d4-a716-446655440282", NOW);
        PaymentRecoveryApproval approval = PaymentRecoveryApproval.approve(
                proposal, 11L, 7L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                "550e8400-e29b-41d4-a716-446655440283", NOW);
        return PaymentRecoveryExecution.authorize(proposal, approval, NOW);
    }
}
