package com.miriyum.domain.platformoperator.paymentrecovery.entity;

public final class PaymentRecoveryEnums {
    private PaymentRecoveryEnums() {
    }

    public enum CaseStatus {
        RECONCILIATION_PENDING,
        INVESTIGATING,
        PROPOSED,
        ADDITIONAL_APPROVAL_PENDING,
        EXECUTING,
        VERIFYING,
        COMPLETED,
        HOLD,
        FAILED,
        FAILED_UNRESOLVED
    }

    public enum RecoveryKind {
        DISPOSITION_RESULT_UNKNOWN,
        DISPOSITION_FAILED,
        REFUND_RESULT_UNKNOWN,
        REFUND_FAILED
    }

    public enum ResultStatus {
        UNKNOWN,
        FAILED,
        SUCCEEDED
    }

    public enum RecoveryAction {
        REQUERY_PROVIDER_RESULT,
        RETRY_REFUND
    }

    public enum ApprovalTier {
        SINGLE_OPERATOR,
        ADDITIONAL_SUPER_ADMIN
    }

    public enum ExecutionStatus {
        PENDING,
        PROCESSING,
        VERIFYING,
        SUCCEEDED,
        FAILED,
        HOLD
    }
}
