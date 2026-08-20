package com.miriyum.domain.platformoperator.paymentrecovery.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ExecutionStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryExecution;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class PaymentRecoveryResponses {
    private PaymentRecoveryResponses() {
    }

    public record CaseSummary(
            String caseId, CaseStatus status, long caseVersion, RecoveryKind kind,
            ResultStatus resultStatus, long originalAmountMinor,
            long cumulativeRefundedAmountMinor, long remainingRefundableAmountMinor,
            String currency, String maskedProviderReference, Set<RecoveryAction> allowedActions,
            long handoffVersion, long paymentVersion, long recoveryVersion,
            Long assignedOperatorId, Instant createdAt, Instant updatedAt) {
        public static CaseSummary from(PaymentRecoveryCase value, Long assignedOperatorId) {
            return new CaseSummary(value.getPublicId(), value.getStatus(), value.getCaseVersion(),
                    value.getRecoveryKind(), value.getResultStatus(), value.getOriginalAmountMinor(),
                    value.getCumulativeRefundedAmountMinor(), value.getRemainingRefundableAmountMinor(),
                    value.getCurrency(), value.getMaskedProviderReference(), Set.copyOf(value.getAllowedActions()),
                    value.getHandoffVersion(), value.getPaymentVersion(), value.getRecoveryVersion(),
                    assignedOperatorId, value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record ProposalData(
            long proposalVersion, RecoveryAction action, long requestedAmountMinor,
            long cumulativeLineageAmountMinor, long originalAmountMinor, String currency,
            ApprovalTier approvalTier, long requesterOperatorId, Long approverOperatorId,
            Instant createdAt) {
        public static ProposalData from(PaymentRecoveryProposal value, Long approverId) {
            return new ProposalData(value.getProposalVersion(), value.getAction(),
                    value.getRequestedAmountMinor(), value.getCumulativeLineageAmountMinor(),
                    value.getOriginalAmountMinor(), value.getCurrency(), value.getApprovalTier(),
                    value.getRequesterPlatformOperatorAccountId(), approverId, value.getCreatedAt());
        }
    }

    public record ExecutionData(
            String executionId, RecoveryAction operation, ExecutionStatus status,
            String maskedOutcome, Instant createdAt, Instant updatedAt) {
        public static ExecutionData from(PaymentRecoveryExecution value) {
            return new ExecutionData(value.getExecutionKey(), value.getOperation(), value.getStatus(),
                    value.getMaskedOutcome(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record CaseDetail(@JsonUnwrapped CaseSummary summary, List<ProposalData> proposals,
                             List<ExecutionData> executions) {
        public CaseDetail {
            proposals = List.copyOf(proposals);
            executions = List.copyOf(executions);
        }
    }

    public record CasePage(List<CaseSummary> content, int page, int size,
                           long totalElements, int totalPages) {
        public CasePage { content = List.copyOf(content); }
    }
}
