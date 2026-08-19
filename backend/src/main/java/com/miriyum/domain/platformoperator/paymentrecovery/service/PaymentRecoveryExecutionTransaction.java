package com.miriyum.domain.platformoperator.paymentrecovery.service;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditAction;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ExecutionStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryExecution;
import com.miriyum.domain.platformoperator.paymentrecovery.exception.PaymentRecoveryErrorCode;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryExecutionRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriter.RecoveryEvent;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PaymentRecoveryExecutionTransaction {
    private static final int MAX_LOOKUPS = 5;
    private final PaymentRecoveryExecutionRepository executions;
    private final PaymentRecoveryCaseRepository cases;
    private final OperatorAuthorityReader authorities;
    private final AdminCaseAssignmentVerifier assignments;
    private final AdminCaseAssignmentManager assignmentManager;
    private final PlatformOperatorAuditWriter audit;
    private final Clock clock;

    public PaymentRecoveryExecutionTransaction(
            PaymentRecoveryExecutionRepository executions, PaymentRecoveryCaseRepository cases,
            OperatorAuthorityReader authorities, AdminCaseAssignmentVerifier assignments,
            AdminCaseAssignmentManager assignmentManager,
            PlatformOperatorAuditWriter audit, Clock clock) {
        this.executions = executions;
        this.cases = cases;
        this.authorities = authorities;
        this.assignments = assignments;
        this.assignmentManager = assignmentManager;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Optional<Claim> claim(String owner) {
        Instant now = clock.instant();
        List<PaymentRecoveryExecution> due = executions.findDueForUpdate(
                Set.of(ExecutionStatus.PENDING, ExecutionStatus.VERIFYING, ExecutionStatus.PROCESSING),
                now, PageRequest.of(0, 1));
        if (due.isEmpty()) return Optional.empty();
        PaymentRecoveryExecution execution = due.getFirst();
        PaymentRecoveryCase recoveryCase = lockedCase(execution.getCasePublicId());
        requireAuthorizedCaseState(execution, recoveryCase);
        long token = execution.claim(owner, now, now.plus(Duration.ofSeconds(30)));
        OperatorAuthority requester;
        try {
            requester = requireActorsAndAssignment(execution, recoveryCase);
        } catch (RuntimeException denied) {
            var before = PaymentRecoveryAuditSnapshots.executionStateSnapshot(recoveryCase, execution);
            execution.markHold(owner, token, "AUTHORIZATION_REJECTED", now);
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.holdFromInvestigation(recoveryCase.getCaseVersion(), now);
            } else {
                recoveryCase.hold(recoveryCase.getCaseVersion(), now);
            }
            append(execution, recoveryCase, Set.of(), Set.of(),
                    PlatformOperatorAuditAction.PAYMENT_RECOVERY_HELD,
                    PlatformOperatorAuditOutcome.DENIED, before);
            return Optional.empty();
        }
        return Optional.of(new Claim(execution.getId(), recoveryCase.getPublicId(),
                recoveryCase.getHandoffId(), execution.getOperation(), execution.getOperationId(),
                execution.getExpectedHandoffVersion(), execution.getExpectedPaymentVersion(),
                execution.getExpectedRecoveryVersion(), owner, token,
                requester.roles(), requester.permissions()));
    }

    @Transactional
    public void scheduleLookup(Claim claim, String outcome) {
        Instant now = clock.instant();
        PaymentRecoveryExecution execution = lockedExecution(claim);
        PaymentRecoveryCase recoveryCase = lockedCase(execution.getCasePublicId());
        long previousCaseVersion = recoveryCase.getCaseVersion();
        var before = PaymentRecoveryAuditSnapshots.executionStateSnapshot(recoveryCase, execution);
        execution.markUnknown(claim.owner(), claim.leaseToken(), now, now.plusSeconds(10));
        if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
            recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
        }
        inheritAssignmentIfAdvanced(recoveryCase, previousCaseVersion,
                execution.getRequesterPlatformOperatorAccountId(), now);
        append(claim, execution, recoveryCase,
                PlatformOperatorAuditAction.PAYMENT_RECOVERY_EXECUTED,
                PlatformOperatorAuditOutcome.SUCCESS, before);
    }

    @Transactional
    public void recordInspection(Claim claim, ManualRecoveryInspection inspection) {
        Instant now = clock.instant();
        PaymentRecoveryExecution execution = lockedExecution(claim);
        PaymentRecoveryCase recoveryCase = lockedCase(execution.getCasePublicId());
        long previousCaseVersion = recoveryCase.getCaseVersion();
        var before = PaymentRecoveryAuditSnapshots.executionStateSnapshot(recoveryCase, execution);
        if (!recoveryCase.getHandoffId().equals(inspection.handoffId())) conflict();
        recoveryCase.applyInspection(
                com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind
                        .valueOf(inspection.kind().name()),
                com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus
                        .valueOf(inspection.resultStatus().name()),
                inspection.originalAmountMinor(), inspection.cumulativeRefundedAmountMinor(),
                inspection.remainingRefundableAmountMinor(), inspection.currency(),
                inspection.allowedActions().stream()
                        .map(action -> RecoveryAction.valueOf(action.name()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                inspection.maskedProviderReference(), inspection.handoffVersion(),
                inspection.paymentVersion(), inspection.recoveryVersion());
        if (inspection.resultStatus() == ManualRecoveryResultStatus.SUCCEEDED) {
            execution.markSucceeded(claim.owner(), claim.leaseToken(), "SUCCEEDED", now);
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
            }
            recoveryCase.complete(recoveryCase.getCaseVersion(), now);
            append(claim, execution, recoveryCase,
                    PlatformOperatorAuditAction.PAYMENT_RECOVERY_VERIFIED,
                    PlatformOperatorAuditOutcome.SUCCESS, before);
        } else if (inspection.resultStatus() == ManualRecoveryResultStatus.FAILED) {
            execution.markFailed(claim.owner(), claim.leaseToken(), "PROVIDER_FAILED", now);
            recoveryCase.fail(recoveryCase.getCaseVersion(), now);
            append(claim, execution, recoveryCase,
                    PlatformOperatorAuditAction.PAYMENT_RECOVERY_VERIFIED,
                    PlatformOperatorAuditOutcome.FAILED, before);
        } else if (execution.getLookupAttemptCount() >= MAX_LOOKUPS) {
            execution.markHold(claim.owner(), claim.leaseToken(), "LOOKUP_EXHAUSTED", now);
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
            }
            recoveryCase.hold(recoveryCase.getCaseVersion(), now);
            append(claim, execution, recoveryCase,
                    PlatformOperatorAuditAction.PAYMENT_RECOVERY_HELD,
                    PlatformOperatorAuditOutcome.SUCCESS, before);
        } else {
            execution.markUnknown(claim.owner(), claim.leaseToken(), now,
                    now.plusSeconds(Math.min(300L, 10L << execution.getLookupAttemptCount())));
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
            }
            append(claim, execution, recoveryCase,
                    PlatformOperatorAuditAction.PAYMENT_RECOVERY_EXECUTED,
                    PlatformOperatorAuditOutcome.SUCCESS, before);
        }
        inheritAssignmentIfAdvanced(recoveryCase, previousCaseVersion,
                execution.getRequesterPlatformOperatorAccountId(), now);
    }

    @Transactional
    public void recordFailure(Claim claim) {
        if (claim.operation() == RecoveryAction.RETRY_REFUND) {
            scheduleLookup(claim, "RESPONSE_LOST");
            return;
        }
        Instant now = clock.instant();
        PaymentRecoveryExecution execution = lockedExecution(claim);
        PaymentRecoveryCase recoveryCase = lockedCase(execution.getCasePublicId());
        long previousCaseVersion = recoveryCase.getCaseVersion();
        var before = PaymentRecoveryAuditSnapshots.executionStateSnapshot(recoveryCase, execution);
        if (execution.getLookupAttemptCount() >= MAX_LOOKUPS) {
            execution.markHold(claim.owner(), claim.leaseToken(), "LOOKUP_EXHAUSTED", now);
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
            }
            recoveryCase.hold(recoveryCase.getCaseVersion(), now);
            inheritAssignmentIfAdvanced(recoveryCase, previousCaseVersion,
                    execution.getRequesterPlatformOperatorAccountId(), now);
            append(claim, execution, recoveryCase,
                    PlatformOperatorAuditAction.PAYMENT_RECOVERY_HELD,
                    PlatformOperatorAuditOutcome.SUCCESS, before);
            return;
        }
        execution.markUnknown(claim.owner(), claim.leaseToken(), now,
                now.plusSeconds(Math.min(300L, 10L << execution.getLookupAttemptCount())));
        if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
            recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
        }
        inheritAssignmentIfAdvanced(recoveryCase, previousCaseVersion,
                execution.getRequesterPlatformOperatorAccountId(), now);
        append(claim, execution, recoveryCase,
                PlatformOperatorAuditAction.PAYMENT_RECOVERY_EXECUTED,
                PlatformOperatorAuditOutcome.SUCCESS, before);
    }

    @Transactional
    public void recordProviderFailure(Claim claim) {
        Instant now = clock.instant();
        PaymentRecoveryExecution execution = lockedExecution(claim);
        PaymentRecoveryCase recoveryCase = lockedCase(execution.getCasePublicId());
        long previousCaseVersion = recoveryCase.getCaseVersion();
        var before = PaymentRecoveryAuditSnapshots.executionStateSnapshot(recoveryCase, execution);
        execution.markFailed(claim.owner(), claim.leaseToken(), "PROVIDER_FAILED", now);
        recoveryCase.fail(recoveryCase.getCaseVersion(), now);
        inheritAssignmentIfAdvanced(recoveryCase, previousCaseVersion,
                execution.getRequesterPlatformOperatorAccountId(), now);
        append(claim, execution, recoveryCase,
                PlatformOperatorAuditAction.PAYMENT_RECOVERY_EXECUTED,
                PlatformOperatorAuditOutcome.FAILED, before);
    }

    private static void requireAuthorizedCaseState(
            PaymentRecoveryExecution execution, PaymentRecoveryCase recoveryCase) {
        boolean expectedCaseState = recoveryCase.getStatus() == CaseStatus.EXECUTING
                && recoveryCase.getCaseVersion() > execution.getAuthorizedCaseVersion();
        expectedCaseState |= recoveryCase.getStatus() == CaseStatus.VERIFYING
                && recoveryCase.getCaseVersion() > execution.getAuthorizedCaseVersion();
        if (execution.getProposalVersion() != null
                && recoveryCase.getCurrentProposalVersion() != execution.getProposalVersion()) {
            expectedCaseState = false;
        }
        if (!expectedCaseState) conflict();
    }

    private OperatorAuthority requireActorsAndAssignment(
            PaymentRecoveryExecution execution, PaymentRecoveryCase recoveryCase) {
        var requester = authorities.requireCurrentAuthority(
                execution.getRequesterPlatformOperatorAccountId(), execution.getRequesterAuthorityVersion());
        if (!requester.permissions().contains(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE)) conflict();
        assignments.verify(new AdminCaseAssignmentRequest(AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), recoveryCase.getCaseVersion(),
                execution.getRequesterPlatformOperatorAccountId()));
        if (execution.getApproverPlatformOperatorAccountId()
                != execution.getRequesterPlatformOperatorAccountId()) {
            var approver = authorities.requireCurrentAuthority(
                    execution.getApproverPlatformOperatorAccountId(), execution.getApproverAuthorityVersion());
            if (!approver.permissions().contains(
                    PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE)) conflict();
        }
        return requester;
    }

    private void append(Claim claim, PaymentRecoveryExecution execution,
                        PaymentRecoveryCase recoveryCase, PlatformOperatorAuditAction action,
                        PlatformOperatorAuditOutcome outcome, java.util.Map<String, Object> before) {
        append(execution, recoveryCase, claim.requesterRoles(), claim.requesterPermissions(),
                action, outcome, before);
    }

    private void append(PaymentRecoveryExecution execution, PaymentRecoveryCase recoveryCase,
                        Set<PlatformOperatorRole> roles,
                        Set<PlatformOperatorPermission> permissions,
                        PlatformOperatorAuditAction action, PlatformOperatorAuditOutcome outcome,
                        java.util.Map<String, Object> before) {
        AdminAuditContext context = new AdminAuditContext(
                execution.getRequesterPlatformOperatorAccountId(), roles, permissions,
                execution.getRequesterAuthorityVersion(), AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), recoveryCase.getCaseVersion(),
                AdminCommandPurpose.PAYMENT_RECOVERY, AdminTargetType.PAYMENT_RECOVERY_CASE,
                execution.getExecutionKey(), null,
                "payment-recovery-worker:" + execution.getExecutionKey());
        audit.appendRecovery(new RecoveryEvent(context, action, outcome,
                PlatformOperatorAuditReason.PAYMENT_RECOVERY, "PAYMENT_RECOVERY_EXECUTION",
                execution.getExecutionKey(), null, before,
                PaymentRecoveryAuditSnapshots.executionStateSnapshot(recoveryCase, execution)));
    }

    private PaymentRecoveryExecution lockedExecution(Claim claim) {
        PaymentRecoveryExecution execution = executions.findByIdForUpdate(claim.executionId())
                .orElseThrow(() -> new ServiceException(PaymentRecoveryErrorCode.RECOVERY_EXECUTION_NOT_FOUND));
        if (!execution.getCasePublicId().equals(claim.caseId())) conflict();
        return execution;
    }

    private PaymentRecoveryCase lockedCase(String caseId) {
        return cases.findByPublicIdForUpdate(caseId)
                .orElseThrow(() -> new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_NOT_FOUND));
    }

    private void inheritAssignmentIfAdvanced(
            PaymentRecoveryCase recoveryCase, long previousCaseVersion,
            long operatorId, Instant now) {
        if (recoveryCase.getCaseVersion() == previousCaseVersion) return;
        assignmentManager.assign(new AdminCaseAssignmentCommand(AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), recoveryCase.getCaseVersion(), operatorId,
                now.plus(Duration.ofMinutes(30))));
    }

    private static void conflict() {
        throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT);
    }

    public record Claim(long executionId, String caseId, String handoffId,
                        RecoveryAction operation, String operationId,
                        long expectedHandoffVersion, long expectedPaymentVersion,
                        long expectedRecoveryVersion, String owner, long leaseToken,
                        Set<PlatformOperatorRole> requesterRoles,
                        Set<PlatformOperatorPermission> requesterPermissions) {
        public Claim {
            requesterRoles = Set.copyOf(requesterRoles);
            requesterPermissions = Set.copyOf(requesterPermissions);
        }
    }
}
