package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static java.util.Map.entry;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryExecution;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PaymentRecoveryAuditSnapshots {
    private PaymentRecoveryAuditSnapshots() {
    }

    public static Map<String, Object> caseSnapshot(PaymentRecoveryCase value) {
        return Map.ofEntries(
                entry("caseId", value.getPublicId()),
                entry("caseVersion", value.getCaseVersion()),
                entry("status", value.getStatus().name()),
                entry("recoveryKind", value.getRecoveryKind().name()),
                entry("resultStatus", value.getResultStatus().name()),
                entry("originalAmountMinor", value.getOriginalAmountMinor()),
                entry("cumulativeRefundedAmountMinor", value.getCumulativeRefundedAmountMinor()),
                entry("remainingRefundableAmountMinor", value.getRemainingRefundableAmountMinor()),
                entry("currency", value.getCurrency()),
                entry("maskedProviderReference",
                        value.getMaskedProviderReference() == null ? "UNAVAILABLE"
                                : value.getMaskedProviderReference()),
                entry("allowedActions", value.getAllowedActions().stream().map(Enum::name).sorted().toList()),
                entry("handoffVersion", value.getHandoffVersion()),
                entry("paymentVersion", value.getPaymentVersion()),
                entry("recoveryVersion", value.getRecoveryVersion()));
    }

    public static Map<String, Object> proposalSnapshot(
            PaymentRecoveryCase recoveryCase, PaymentRecoveryProposal proposal, Long approverId) {
        Map<String, Object> snapshot = new LinkedHashMap<>(caseSnapshot(recoveryCase));
        snapshot.put("proposalVersion", proposal.getProposalVersion());
        snapshot.put("action", proposal.getAction().name());
        snapshot.put("requestedAmountMinor", proposal.getRequestedAmountMinor());
        snapshot.put("cumulativeLineageAmountMinor", proposal.getCumulativeLineageAmountMinor());
        snapshot.put("approvalTier", proposal.getApprovalTier().name());
        snapshot.put("requesterOperatorId", proposal.getRequesterPlatformOperatorAccountId());
        snapshot.put("requesterAuthorityVersion", proposal.getRequesterAuthorityVersion());
        if (approverId != null) snapshot.put("approverOperatorId", approverId);
        return Map.copyOf(snapshot);
    }

    public static Map<String, Object> executionSnapshot(
            PaymentRecoveryCase recoveryCase, PaymentRecoveryProposal proposal,
            PaymentRecoveryExecution execution) {
        Map<String, Object> snapshot = new LinkedHashMap<>(proposalSnapshot(
                recoveryCase, proposal, execution.getApproverPlatformOperatorAccountId()));
        snapshot.put("executionKey", execution.getExecutionKey());
        snapshot.put("operation", execution.getOperation().name());
        snapshot.put("executionStatus", execution.getStatus().name());
        snapshot.put("authorizedCaseVersion", execution.getAuthorizedCaseVersion());
        snapshot.put("approverAuthorityVersion", execution.getApproverAuthorityVersion());
        return Map.copyOf(snapshot);
    }

    public static Map<String, Object> executionStateSnapshot(
            PaymentRecoveryCase recoveryCase, PaymentRecoveryExecution execution) {
        Map<String, Object> snapshot = new LinkedHashMap<>(caseSnapshot(recoveryCase));
        if (execution.getProposalVersion() != null) {
            snapshot.put("proposalVersion", execution.getProposalVersion());
        }
        snapshot.put("executionKey", execution.getExecutionKey());
        snapshot.put("operation", execution.getOperation().name());
        snapshot.put("executionStatus", execution.getStatus().name());
        snapshot.put("authorizedCaseVersion", execution.getAuthorizedCaseVersion());
        snapshot.put("requesterOperatorId", execution.getRequesterPlatformOperatorAccountId());
        snapshot.put("requesterAuthorityVersion", execution.getRequesterAuthorityVersion());
        snapshot.put("approverOperatorId", execution.getApproverPlatformOperatorAccountId());
        snapshot.put("approverAuthorityVersion", execution.getApproverAuthorityVersion());
        snapshot.put("attemptCount", execution.getAttemptCount());
        snapshot.put("lookupAttemptCount", execution.getLookupAttemptCount());
        snapshot.put("maskedOutcome",
                execution.getMaskedOutcome() == null ? "PENDING" : execution.getMaskedOutcome());
        return Map.copyOf(snapshot);
    }
}
