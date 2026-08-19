package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRefundPreview;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ProposalRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryApprovalRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryExecutionRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryProposalRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentRecoveryCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440282";
    @Mock PaymentRecoveryCaseRepository cases;
    @Mock PaymentRecoveryProposalRepository proposals;
    @Mock PaymentRecoveryApprovalRepository approvals;
    @Mock PaymentRecoveryExecutionRepository executions;
    @Mock AdminCaseAssignmentManager assignments;
    @Mock HighRiskCommandGuard guard;
    @Mock PaymentService payments;
    @Mock PlatformOperatorAuditWriter audit;
    @Mock IdempotencyExecutor idempotency;
    private PaymentRecoveryCommandService service;
    private PlatformOperatorPrincipal principal;

    @BeforeEach
    void setUp() {
        service = new PaymentRecoveryCommandService(cases, proposals, approvals, executions,
                assignments, guard, payments, new PaymentRecoveryRequestFingerprint(), audit,
                idempotency, Clock.fixed(NOW, ZoneOffset.UTC));
        principal = new PlatformOperatorPrincipal(11L, "operator@example.com", "session", 7L, 1L, false);
        when(idempotency.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<BusinessResult<Object>> work = invocation.getArgument(1);
            work.get();
            return org.mockito.Mockito.mock(IdempotentOutcome.class);
        });
    }

    @Test
    void highValueProposalWaitsForDifferentSuperAdminAndCreatesNoExecution() {
        PaymentRecoveryCase recoveryCase = investigating();
        when(cases.findByPublicIdForUpdate(recoveryCase.getPublicId())).thenReturn(Optional.of(recoveryCase));
        when(guard.authorize(any())).thenReturn(context());
        when(payments.previewManualRecoveryRefund(any())).thenReturn(new ManualRecoveryRefundPreview(
                "281", 3L, 4L, 5L, 300_000L, 100_001L, 100_000L,
                200_000L, "KRW", true));
        when(proposals.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.propose(command(), principal, recoveryCase.getPublicId(),
                new ProposalRequest(RecoveryAction.RETRY_REFUND, 2L, 3L, 4L, 5L),
                "approval", "corr-281");

        assertThat(recoveryCase.getStatus()).isEqualTo(CaseStatus.ADDITIONAL_APPROVAL_PENDING);
        verify(approvals, never()).saveAndFlush(any());
        verify(executions, never()).saveAndFlush(any());
        verify(proposals).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                proposal -> proposal.getApprovalTier() == ApprovalTier.ADDITIONAL_SUPER_ADMIN
                        && proposal.getCumulativeLineageAmountMinor() == 200_001L));
        verify(assignments).assign(org.mockito.ArgumentMatchers.argThat(
                (AdminCaseAssignmentCommand assignment) ->
                        assignment.caseId().equals(recoveryCase.getPublicId())
                                && assignment.caseVersion() == recoveryCase.getCaseVersion()
                                && assignment.operatorId() == principal.accountId()));
    }

    @Test
    void ordinaryProposalSelfApprovesAndQueuesOneDurableExecution() {
        PaymentRecoveryCase recoveryCase = investigating();
        when(cases.findByPublicIdForUpdate(recoveryCase.getPublicId())).thenReturn(Optional.of(recoveryCase));
        when(guard.authorize(any())).thenReturn(context());
        when(payments.previewManualRecoveryRefund(any())).thenReturn(new ManualRecoveryRefundPreview(
                "281", 3L, 4L, 5L, 300_000L, 100_000L, 100_000L,
                200_000L, "KRW", true));
        when(proposals.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(approvals.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.propose(command(), principal, recoveryCase.getPublicId(),
                new ProposalRequest(RecoveryAction.RETRY_REFUND, 2L, 3L, 4L, 5L),
                "approval", "corr-281");

        assertThat(recoveryCase.getStatus()).isEqualTo(CaseStatus.EXECUTING);
        verify(approvals).saveAndFlush(any());
        verify(executions).saveAndFlush(any());
        verify(assignments).assign(org.mockito.ArgumentMatchers.argThat(
                (AdminCaseAssignmentCommand assignment) ->
                        assignment.caseId().equals(recoveryCase.getPublicId())
                                && assignment.caseVersion() == recoveryCase.getCaseVersion()
                                && assignment.operatorId() == principal.accountId()));
    }

    private static PaymentRecoveryCase investigating() {
        PaymentRecoveryCase value = PaymentRecoveryCase.open("281", RecoveryKind.REFUND_FAILED,
                ResultStatus.FAILED, 300_000L, 100_000L, 200_000L, "KRW",
                Set.of(RecoveryAction.RETRY_REFUND), "port********abc", 3L, 4L, 5L, NOW);
        value.beginInvestigation(1L, NOW);
        return value;
    }

    private static AdminAuditContext context() {
        return new AdminAuditContext(11L, Set.of(PlatformOperatorRole.PAYMENT_RECOVERY_OPERATOR),
                Set.of(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE), 7L,
                AdminCaseType.PAYMENT_RECOVERY, "case", 2L, AdminCommandPurpose.PAYMENT_RECOVERY,
                AdminTargetType.PAYMENT_RECOVERY_CASE, "case", "digest", "corr-281");
    }

    private static IdempotencyCommand command() {
        return new IdempotencyCommand("platform-operator", 11L, "PAYMENT_RECOVERY_PROPOSE",
                KEY, "a".repeat(64));
    }
}
