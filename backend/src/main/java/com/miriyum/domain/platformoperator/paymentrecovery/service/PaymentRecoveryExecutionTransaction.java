package com.miriyum.domain.platformoperator.paymentrecovery.service;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ExecutionStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryExecution;
import com.miriyum.domain.platformoperator.paymentrecovery.exception.PaymentRecoveryErrorCode;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryExecutionRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
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
    private final Clock clock;

    public PaymentRecoveryExecutionTransaction(
            PaymentRecoveryExecutionRepository executions, PaymentRecoveryCaseRepository cases,
            OperatorAuthorityReader authorities, AdminCaseAssignmentVerifier assignments, Clock clock) {
        this.executions = executions;
        this.cases = cases;
        this.authorities = authorities;
        this.assignments = assignments;
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
        try {
            requireActorsAndAssignment(execution, recoveryCase);
        } catch (RuntimeException denied) {
            execution.markHold(owner, token, "AUTHORIZATION_REJECTED", now);
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.holdFromInvestigation(recoveryCase.getCaseVersion(), now);
            } else {
                recoveryCase.hold(recoveryCase.getCaseVersion(), now);
            }
            return Optional.empty();
        }
        return Optional.of(new Claim(execution.getId(), recoveryCase.getPublicId(),
                recoveryCase.getHandoffId(), execution.getOperation(), execution.getOperationId(),
                execution.getExpectedHandoffVersion(), execution.getExpectedPaymentVersion(),
                execution.getExpectedRecoveryVersion(), owner, token));
    }

    @Transactional
    public void scheduleLookup(Claim claim, String outcome) {
        Instant now = clock.instant();
        PaymentRecoveryExecution execution = lockedExecution(claim);
        PaymentRecoveryCase recoveryCase = lockedCase(execution.getCasePublicId());
        execution.markUnknown(claim.owner(), claim.leaseToken(), now, now.plusSeconds(10));
        if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
            recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
        }
    }

    @Transactional
    public void recordInspection(Claim claim, ManualRecoveryInspection inspection) {
        Instant now = clock.instant();
        PaymentRecoveryExecution execution = lockedExecution(claim);
        PaymentRecoveryCase recoveryCase = lockedCase(execution.getCasePublicId());
        if (!recoveryCase.getHandoffId().equals(inspection.handoffId())) conflict();
        if (inspection.resultStatus() == ManualRecoveryResultStatus.SUCCEEDED) {
            execution.markSucceeded(claim.owner(), claim.leaseToken(), "SUCCEEDED", now);
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
            }
            recoveryCase.complete(recoveryCase.getCaseVersion(), now);
        } else if (inspection.resultStatus() == ManualRecoveryResultStatus.FAILED) {
            execution.markFailed(claim.owner(), claim.leaseToken(), "PROVIDER_FAILED", now);
            recoveryCase.fail(recoveryCase.getCaseVersion(), now);
        } else if (execution.getLookupAttemptCount() >= MAX_LOOKUPS) {
            execution.markHold(claim.owner(), claim.leaseToken(), "LOOKUP_EXHAUSTED", now);
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
            }
            recoveryCase.hold(recoveryCase.getCaseVersion(), now);
        } else {
            execution.markUnknown(claim.owner(), claim.leaseToken(), now,
                    now.plusSeconds(Math.min(300L, 10L << execution.getLookupAttemptCount())));
            if (recoveryCase.getStatus() == CaseStatus.EXECUTING) {
                recoveryCase.startVerification(recoveryCase.getCaseVersion(), now);
            }
        }
    }

    @Transactional
    public void recordFailure(Claim claim) {
        if (claim.operation() == RecoveryAction.RETRY_REFUND) {
            scheduleLookup(claim, "RESPONSE_LOST");
            return;
        }
        recordInspection(claim, unknownInspection(claim));
    }

    @Transactional
    public void recordProviderFailure(Claim claim) {
        Instant now = clock.instant();
        PaymentRecoveryExecution execution = lockedExecution(claim);
        PaymentRecoveryCase recoveryCase = lockedCase(execution.getCasePublicId());
        execution.markFailed(claim.owner(), claim.leaseToken(), "PROVIDER_FAILED", now);
        recoveryCase.fail(recoveryCase.getCaseVersion(), now);
    }

    private static void requireAuthorizedCaseState(
            PaymentRecoveryExecution execution, PaymentRecoveryCase recoveryCase) {
        boolean expectedCaseState = recoveryCase.getStatus() == CaseStatus.EXECUTING
                && recoveryCase.getCaseVersion() == execution.getAuthorizedCaseVersion();
        expectedCaseState |= recoveryCase.getStatus() == CaseStatus.VERIFYING
                && recoveryCase.getCaseVersion() == execution.getAuthorizedCaseVersion() + 1L;
        if (!expectedCaseState) conflict();
    }

    private void requireActorsAndAssignment(
            PaymentRecoveryExecution execution, PaymentRecoveryCase recoveryCase) {
        var requester = authorities.requireCurrentAuthority(
                execution.getRequesterPlatformOperatorAccountId(), execution.getRequesterAuthorityVersion());
        if (!requester.permissions().contains(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE)) conflict();
        assignments.verify(new AdminCaseAssignmentRequest(AdminCaseType.PAYMENT_RECOVERY,
                recoveryCase.getPublicId(), execution.getAuthorizedCaseVersion(),
                execution.getRequesterPlatformOperatorAccountId()));
        if (execution.getApproverPlatformOperatorAccountId()
                != execution.getRequesterPlatformOperatorAccountId()) {
            var approver = authorities.requireCurrentAuthority(
                    execution.getApproverPlatformOperatorAccountId(), execution.getApproverAuthorityVersion());
            if (!approver.permissions().contains(
                    PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE)) conflict();
        }
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

    private static ManualRecoveryInspection unknownInspection(Claim claim) {
        return new ManualRecoveryInspection(claim.handoffId(), claim.expectedHandoffVersion(),
                claim.expectedPaymentVersion(), claim.expectedRecoveryVersion(),
                com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN,
                1L, 0L, 0L, "KRW", ManualRecoveryResultStatus.UNKNOWN,
                Set.of(com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryAction.REQUERY_PROVIDER_RESULT),
                null);
    }

    private static void conflict() {
        throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT);
    }

    public record Claim(long executionId, String caseId, String handoffId,
                        RecoveryAction operation, String operationId,
                        long expectedHandoffVersion, long expectedPaymentVersion,
                        long expectedRecoveryVersion, String owner, long leaseToken) {
    }
}
