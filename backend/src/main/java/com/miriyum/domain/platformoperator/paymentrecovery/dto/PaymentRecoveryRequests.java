package com.miriyum.domain.platformoperator.paymentrecovery.dto;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class PaymentRecoveryRequests {
    private PaymentRecoveryRequests() {
    }

    public record AssignmentRequest(@Min(1) long expectedCaseVersion) {
    }

    public record RequeryRequest(
            @Min(1) long expectedCaseVersion,
            @Min(0) long expectedHandoffVersion,
            @Min(0) long expectedPaymentVersion,
            @Min(0) long expectedRecoveryVersion) {
    }

    public record ProposalRequest(
            @NotNull RecoveryAction action,
            @Min(1) long expectedCaseVersion,
            @Min(0) long expectedHandoffVersion,
            @Min(0) long expectedPaymentVersion,
            @Min(0) long expectedRecoveryVersion) {
    }

    public record ApprovalRequest(
            @Min(1) long expectedCaseVersion,
            @Min(1) long expectedProposalVersion) {
    }

    public record ClosureRequest(@Min(1) long expectedCaseVersion) {
    }
}
