package com.miriyum.domain.platformoperator.paymentrecovery.service;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.PreviewManualRecoveryRefundQuery;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ApprovalRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.AssignmentRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ClosureRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.ProposalRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryRequests.RequeryRequest;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.CaseSummary;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.ExecutionData;
import com.miriyum.domain.platformoperator.paymentrecovery.dto.PaymentRecoveryResponses.ProposalData;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryApproval;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryExecution;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import com.miriyum.domain.platformoperator.paymentrecovery.exception.PaymentRecoveryErrorCode;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryApprovalRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryExecutionRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryProposalRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter.RecoveryEvent;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PaymentRecoveryCommandService {
    private final PaymentRecoveryCaseRepository cases;
    private final PaymentRecoveryProposalRepository proposals;
    private final PaymentRecoveryApprovalRepository approvals;
    private final PaymentRecoveryExecutionRepository executions;
    private final AdminCaseAssignmentManager assignments;
    private final HighRiskCommandGuard guard;
    private final PaymentService payments;
    private final PaymentRecoveryRequestFingerprint fingerprints;
    private final PlatformOperatorAuditWriter audit;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public PaymentRecoveryCommandService(
            PaymentRecoveryCaseRepository cases, PaymentRecoveryProposalRepository proposals,
            PaymentRecoveryApprovalRepository approvals, PaymentRecoveryExecutionRepository executions,
            AdminCaseAssignmentManager assignments, HighRiskCommandGuard guard, PaymentService payments,
            PaymentRecoveryRequestFingerprint fingerprints, PlatformOperatorAuditWriter audit,
            IdempotencyExecutor idempotency, Clock clock) {
        this.cases = cases; this.proposals = proposals; this.approvals = approvals;
        this.executions = executions; this.assignments = assignments; this.guard = guard;
        this.payments = payments; this.fingerprints = fingerprints; this.audit = audit;
        this.idempotency = idempotency; this.clock = clock;
    }

    @Transactional
    public IdempotentOutcome assign(IdempotencyCommand command, PlatformOperatorPrincipal principal,
                                    String caseId, AssignmentRequest request, String approval,
                                    String correlationId) {
        return idempotency.execute(command, () -> {
            PaymentRecoveryCase recoveryCase = locked(caseId);
            requireVersion(recoveryCase, request.expectedCaseVersion());
            var before = PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase);
            AdminAuditContext context = guard.authorizeInitialPaymentRecoveryAssignment(highRisk(
                    principal, PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE, recoveryCase,
                    caseId, approval, correlationId));
            recoveryCase.beginInvestigation(request.expectedCaseVersion(), clock.instant());
            cases.saveAndFlush(recoveryCase);
            assignments.assign(new AdminCaseAssignmentCommand(AdminCaseType.PAYMENT_RECOVERY,
                    caseId, recoveryCase.getCaseVersion(), principal.accountId(),
                    clock.instant().plus(Duration.ofMinutes(30))));
            append(context, PlatformOperatorAuditAction.PAYMENT_RECOVERY_CASE_ASSIGNED,
                    caseId, command.idempotencyKey(), before,
                    PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase));
            return success(HttpStatus.OK, "PAYMENT_RECOVERY_CASE", caseId,
                    CaseSummary.from(recoveryCase, principal.accountId()));
        });
    }

    @Transactional
    public IdempotentOutcome requery(IdempotencyCommand command, PlatformOperatorPrincipal principal,
                                     String caseId, RequeryRequest request, String approval,
                                     String correlationId) {
        return idempotency.execute(command, () -> {
            PaymentRecoveryCase recoveryCase = locked(caseId);
            requireVersions(recoveryCase, request.expectedCaseVersion(), request.expectedHandoffVersion(),
                    request.expectedPaymentVersion(), request.expectedRecoveryVersion());
            var before = PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase);
            AdminAuditContext context = guard.authorize(highRisk(principal,
                    PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE, recoveryCase,
                    caseId, approval, correlationId));
            PaymentRecoveryExecution execution = PaymentRecoveryExecution.authorizeRequery(
                    recoveryCase, context.operatorId(), context.authorityVersion(), clock.instant());
            recoveryCase.queueRequery(request.expectedCaseVersion(), clock.instant());
            cases.saveAndFlush(recoveryCase);
            inheritAssignment(recoveryCase, context.operatorId());
            executions.saveAndFlush(execution);
            append(context, PlatformOperatorAuditAction.PAYMENT_RECOVERY_REQUERY_REQUESTED,
                    execution.getExecutionKey(), command.idempotencyKey(), before,
                    PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase));
            return success(HttpStatus.ACCEPTED, "PAYMENT_RECOVERY_EXECUTION",
                    execution.getExecutionKey(), ExecutionData.from(execution));
        });
    }

    @Transactional
    public IdempotentOutcome propose(IdempotencyCommand command, PlatformOperatorPrincipal principal,
                                     String caseId, ProposalRequest request, String approval,
                                     String correlationId) {
        return idempotency.execute(command, () -> {
            PaymentRecoveryCase recoveryCase = locked(caseId);
            requireVersions(recoveryCase, request.expectedCaseVersion(), request.expectedHandoffVersion(),
                    request.expectedPaymentVersion(), request.expectedRecoveryVersion());
            if (request.action() != RecoveryAction.RETRY_REFUND
                    || !recoveryCase.getAllowedActions().contains(request.action())) actionDenied();
            AdminAuditContext context = guard.authorize(highRisk(principal,
                    PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE, recoveryCase,
                    caseId, approval, correlationId));
            var preview = payments.previewManualRecoveryRefund(
                    new PreviewManualRecoveryRefundQuery(recoveryCase.getHandoffId()));
            if (!preview.retryable() || preview.handoffVersion() != request.expectedHandoffVersion()
                    || preview.paymentVersion() != request.expectedPaymentVersion()
                    || preview.refundVersion() != request.expectedRecoveryVersion()
                    || preview.originalAmountMinor() != recoveryCase.getOriginalAmountMinor()
                    || !preview.currency().equals(recoveryCase.getCurrency())) actionDenied();
            long cumulative;
            try {
                cumulative = Math.addExact(preview.cumulativeRefundedAmountMinor(),
                        preview.requestedAmountMinor());
            } catch (ArithmeticException overflow) {
                actionDenied(); return null;
            }
            long proposalVersion = recoveryCase.getCurrentProposalVersion() + 1L;
            String fingerprint = fingerprints.proposal(caseId, proposalVersion, request.action(),
                    preview.requestedAmountMinor(), request.expectedCaseVersion(),
                    request.expectedHandoffVersion(), request.expectedPaymentVersion(),
                    request.expectedRecoveryVersion());
            PaymentRecoveryProposal proposal = PaymentRecoveryProposal.propose(caseId,
                    proposalVersion, request.expectedCaseVersion(), request.action(),
                    preview.requestedAmountMinor(), cumulative, preview.originalAmountMinor(),
                    preview.currency(), preview.handoffVersion(), preview.paymentVersion(),
                    preview.refundVersion(), fingerprint, context.operatorId(),
                    context.authorityVersion(), context.roles(), context.permissions(),
                    command.idempotencyKey(), clock.instant());
            var before = PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase);
            proposals.saveAndFlush(proposal);
            recoveryCase.recordProposal(request.expectedCaseVersion(), proposalVersion,
                    proposal.getApprovalTier(), clock.instant());
            Long approverId = null;
            PaymentRecoveryExecution execution = null;
            if (proposal.getApprovalTier() == ApprovalTier.SINGLE_OPERATOR) {
                PaymentRecoveryApproval selfApproval = approvals.saveAndFlush(
                        PaymentRecoveryApproval.approve(proposal, context.operatorId(),
                                context.authorityVersion(), context.roles(), context.permissions(),
                                command.idempotencyKey(), clock.instant()));
                approverId = selfApproval.getApproverPlatformOperatorAccountId();
                recoveryCase.queueExecution(recoveryCase.getCaseVersion(), clock.instant());
                execution = executions.saveAndFlush(PaymentRecoveryExecution.authorize(
                        proposal, selfApproval, clock.instant()));
            }
            cases.saveAndFlush(recoveryCase);
            inheritAssignment(recoveryCase, context.operatorId());
            append(context, PlatformOperatorAuditAction.PAYMENT_RECOVERY_PROPOSED,
                    caseId + ":" + proposalVersion, command.idempotencyKey(), before,
                    PaymentRecoveryAuditSnapshots.proposalSnapshot(
                            recoveryCase, proposal, approverId));
            if (execution != null) {
                append(context, PlatformOperatorAuditAction.PAYMENT_RECOVERY_EXECUTION_QUEUED,
                        execution.getExecutionKey(), command.idempotencyKey(),
                        PaymentRecoveryAuditSnapshots.proposalSnapshot(
                                recoveryCase, proposal, approverId),
                        PaymentRecoveryAuditSnapshots.executionSnapshot(
                                recoveryCase, proposal, execution));
            }
            return success(HttpStatus.CREATED, "PAYMENT_RECOVERY_PROPOSAL",
                    caseId + ":" + proposalVersion, ProposalData.from(proposal, approverId));
        });
    }

    @Transactional
    public IdempotentOutcome approve(IdempotencyCommand command, PlatformOperatorPrincipal principal,
                                     String caseId, long proposalVersion, ApprovalRequest request,
                                     String approval, String correlationId) {
        return idempotency.execute(command, () -> {
            PaymentRecoveryCase recoveryCase = locked(caseId);
            requireVersion(recoveryCase, request.expectedCaseVersion());
            if (proposalVersion != request.expectedProposalVersion()) conflict();
            PaymentRecoveryProposal proposal = proposals.findByCasePublicIdAndProposalVersion(
                            caseId, proposalVersion)
                    .orElseThrow(() -> new ServiceException(
                            PaymentRecoveryErrorCode.RECOVERY_PROPOSAL_NOT_FOUND));
            AdminAuditContext context = guard.authorizeSecondaryPaymentRecoveryCommand(highRisk(
                    principal, PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE,
                    recoveryCase, caseId + ":proposal:" + proposalVersion, approval, correlationId));
            requireSuperAdmin(context);
            var before = PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase);
            PaymentRecoveryApproval approved = approvals.saveAndFlush(PaymentRecoveryApproval.approve(
                    proposal, context.operatorId(), context.authorityVersion(), context.roles(),
                    context.permissions(), command.idempotencyKey(), clock.instant()));
            recoveryCase.recordAdditionalApproval(request.expectedCaseVersion(), clock.instant());
            recoveryCase.queueExecution(recoveryCase.getCaseVersion(), clock.instant());
            cases.saveAndFlush(recoveryCase);
            inheritAssignment(recoveryCase, proposal.getRequesterPlatformOperatorAccountId());
            PaymentRecoveryExecution execution = executions.saveAndFlush(
                    PaymentRecoveryExecution.authorize(proposal, approved, clock.instant()));
            append(context, PlatformOperatorAuditAction.PAYMENT_RECOVERY_APPROVED,
                    execution.getExecutionKey(), command.idempotencyKey(), before,
                    PaymentRecoveryAuditSnapshots.proposalSnapshot(
                            recoveryCase, proposal, approved.getApproverPlatformOperatorAccountId()));
            append(context, PlatformOperatorAuditAction.PAYMENT_RECOVERY_EXECUTION_QUEUED,
                    execution.getExecutionKey(), command.idempotencyKey(),
                    PaymentRecoveryAuditSnapshots.proposalSnapshot(
                            recoveryCase, proposal, approved.getApproverPlatformOperatorAccountId()),
                    PaymentRecoveryAuditSnapshots.executionSnapshot(
                            recoveryCase, proposal, execution));
            return success(HttpStatus.OK, "PAYMENT_RECOVERY_EXECUTION",
                    execution.getExecutionKey(), ExecutionData.from(execution));
        });
    }

    @Transactional
    public IdempotentOutcome closeUnresolved(
            IdempotencyCommand command, PlatformOperatorPrincipal principal, String caseId,
            ClosureRequest request, String approval, String correlationId) {
        return idempotency.execute(command, () -> {
            PaymentRecoveryCase recoveryCase = locked(caseId);
            requireVersion(recoveryCase, request.expectedCaseVersion());
            AdminAuditContext context = guard.authorizeSecondaryPaymentRecoveryCommand(highRisk(
                    principal, PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE,
                    recoveryCase, caseId + ":closure", approval, correlationId));
            requireSuperAdmin(context);
            executions.findByCasePublicIdOrderByCreatedAtAsc(caseId).stream().reduce((a, b) -> b)
                    .filter(last -> last.getRequesterPlatformOperatorAccountId() == context.operatorId())
                    .ifPresent(last -> { throw new ServiceException(
                            PaymentRecoveryErrorCode.RECOVERY_APPROVER_MUST_DIFFER); });
            var before = PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase);
            recoveryCase.closeUnresolved(request.expectedCaseVersion(), clock.instant());
            cases.saveAndFlush(recoveryCase);
            inheritAssignment(recoveryCase, context.operatorId());
            append(context, PlatformOperatorAuditAction.PAYMENT_RECOVERY_CLOSED,
                    caseId, command.idempotencyKey(), before,
                    PaymentRecoveryAuditSnapshots.caseSnapshot(recoveryCase));
            return success(HttpStatus.OK, "PAYMENT_RECOVERY_CASE", caseId,
                    CaseSummary.from(recoveryCase, null));
        });
    }

    private PaymentRecoveryCase locked(String caseId) {
        return cases.findByPublicIdForUpdate(caseId)
                .orElseThrow(() -> new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_NOT_FOUND));
    }

    private void inheritAssignment(PaymentRecoveryCase recoveryCase, long operatorId) {
        assignments.assign(new AdminCaseAssignmentCommand(AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), recoveryCase.getCaseVersion(), operatorId,
                clock.instant().plus(Duration.ofMinutes(30))));
    }

    private HighRiskCommandRequest highRisk(
            PlatformOperatorPrincipal principal, PlatformOperatorPermission permission,
            PaymentRecoveryCase recoveryCase, String targetId, String approval, String correlationId) {
        return new HighRiskCommandRequest(principal, permission, AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), recoveryCase.getCaseVersion(),
                AdminCommandPurpose.PAYMENT_RECOVERY, AdminTargetType.PAYMENT_RECOVERY_CASE,
                targetId, approval, correlationId);
    }

    private void append(AdminAuditContext context, PlatformOperatorAuditAction action,
                        String targetId, String key, Map<String, Object> before,
                        Map<String, Object> after) {
        audit.appendRecovery(new RecoveryEvent(context, action,
                PlatformOperatorAuditOutcome.SUCCESS, PlatformOperatorAuditReason.PAYMENT_RECOVERY,
                "PAYMENT_RECOVERY_CASE", targetId, key, before, after));
    }

    private static void requireVersions(PaymentRecoveryCase value, long caseVersion,
                                        long handoffVersion, long paymentVersion,
                                        long recoveryVersion) {
        requireVersion(value, caseVersion);
        if (value.getHandoffVersion() != handoffVersion
                || value.getPaymentVersion() != paymentVersion
                || value.getRecoveryVersion() != recoveryVersion) conflict();
    }

    private static void requireVersion(PaymentRecoveryCase value, long expected) {
        if (value.getCaseVersion() != expected) conflict();
    }

    private static void requireSuperAdmin(AdminAuditContext context) {
        if (!context.roles().contains(PlatformOperatorRole.SUPER_ADMIN)
                || !context.permissions().contains(
                PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE)) {
            throw new ServiceException(
                    com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }

    private static void actionDenied() {
        throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_ACTION_NOT_ALLOWED);
    }

    private static void conflict() {
        throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT);
    }

    private static <T> BusinessResult<T> success(
            HttpStatus status, String resourceType, String resourceId, T data) {
        return new BusinessResult<>(status.value(), "SUCCESS", resourceType, resourceId, data);
    }
}
