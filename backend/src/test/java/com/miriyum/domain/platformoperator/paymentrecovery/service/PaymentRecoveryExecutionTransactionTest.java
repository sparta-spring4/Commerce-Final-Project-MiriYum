package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryApproval;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ExecutionStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryExecution;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryExecutionRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentRecoveryExecutionTransactionTest {
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
    @Mock PaymentRecoveryExecutionRepository executions;
    @Mock PaymentRecoveryCaseRepository cases;
    @Mock OperatorAuthorityReader authorities;
    @Mock AdminCaseAssignmentVerifier assignments;

    @Test
    void revokedAuthorityMovesExecutionAndCaseToHoldBeforeExternalWork() {
        PaymentRecoveryCase recoveryCase = PaymentRecoveryCase.open(
                "281", RecoveryKind.REFUND_FAILED, ResultStatus.FAILED,
                300_000L, 0L, 300_000L, "KRW", Set.of(RecoveryAction.RETRY_REFUND),
                "port********abc", 3L, 4L, 5L, NOW);
        recoveryCase.beginInvestigation(1L, NOW);
        PaymentRecoveryProposal proposal = PaymentRecoveryProposal.propose(
                recoveryCase.getPublicId(), 1L, 2L, RecoveryAction.RETRY_REFUND,
                100_000L, 100_000L, 300_000L, "KRW", 3L, 4L, 5L,
                "a".repeat(64), 11L, 7L,
                Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                UUID.randomUUID().toString(), NOW);
        recoveryCase.recordProposal(2L, 1L, proposal.getApprovalTier(), NOW);
        PaymentRecoveryApproval approval = PaymentRecoveryApproval.approve(
                proposal, 11L, 7L, Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE),
                UUID.randomUUID().toString(), NOW);
        recoveryCase.queueExecution(3L, NOW);
        PaymentRecoveryExecution execution = PaymentRecoveryExecution.authorize(
                proposal, approval, NOW);
        when(executions.findDueForUpdate(any(), any(), any())).thenReturn(List.of(execution));
        when(cases.findByPublicIdForUpdate(recoveryCase.getPublicId()))
                .thenReturn(Optional.of(recoveryCase));
        when(authorities.requireCurrentAuthority(11L, 7L)).thenThrow(
                new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
        var transaction = new PaymentRecoveryExecutionTransaction(executions, cases,
                authorities, assignments, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(transaction.claim("worker-281")).isEmpty();
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.HOLD);
        assertThat(recoveryCase.getStatus()).isEqualTo(CaseStatus.HOLD);
    }
}
