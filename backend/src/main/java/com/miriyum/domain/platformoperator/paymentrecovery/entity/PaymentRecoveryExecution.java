package com.miriyum.domain.platformoperator.paymentrecovery.entity;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ExecutionStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.exception.PaymentRecoveryErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_recovery_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRecoveryExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_recovery_execution_id")
    private Long id;
    @Column(name = "case_public_id", nullable = false, length = 36)
    private String casePublicId;
    @Column(name = "proposal_version")
    private Long proposalVersion;
    @Column(name = "execution_key", nullable = false, length = 36)
    private String executionKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RecoveryAction operation;
    @Column(name = "operation_id", nullable = false, length = 36)
    private String operationId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;
    @Column(name = "authorized_case_version", nullable = false)
    private long authorizedCaseVersion;
    @Column(name = "expected_handoff_version", nullable = false)
    private long expectedHandoffVersion;
    @Column(name = "expected_payment_version", nullable = false)
    private long expectedPaymentVersion;
    @Column(name = "expected_recovery_version", nullable = false)
    private long expectedRecoveryVersion;
    @Column(name = "requester_platform_operator_account_id", nullable = false)
    private long requesterPlatformOperatorAccountId;
    @Column(name = "requester_authority_version", nullable = false)
    private long requesterAuthorityVersion;
    @Column(name = "approver_platform_operator_account_id", nullable = false)
    private long approverPlatformOperatorAccountId;
    @Column(name = "approver_authority_version", nullable = false)
    private long approverAuthorityVersion;
    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;
    @Column(name = "lease_token", nullable = false)
    private long leaseToken;
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "lookup_attempt_count", nullable = false)
    private int lookupAttemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "masked_outcome", length = 50)
    private String maskedOutcome;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public static PaymentRecoveryExecution authorize(
            PaymentRecoveryProposal proposal, PaymentRecoveryApproval approval,
            long authorizedCaseVersion, Instant now) {
        Objects.requireNonNull(proposal);
        Objects.requireNonNull(approval);
        if (!proposal.getCasePublicId().equals(approval.getCasePublicId())
                || proposal.getProposalVersion() != approval.getProposalVersion()
                || authorizedCaseVersion < 1) conflict();
        PaymentRecoveryExecution value = new PaymentRecoveryExecution();
        value.casePublicId = proposal.getCasePublicId();
        value.proposalVersion = proposal.getProposalVersion();
        value.executionKey = UUID.randomUUID().toString();
        value.operation = RecoveryAction.RETRY_REFUND;
        value.operationId = UUID.randomUUID().toString();
        value.status = ExecutionStatus.PENDING;
        value.authorizedCaseVersion = authorizedCaseVersion;
        value.expectedHandoffVersion = proposal.getExpectedHandoffVersion();
        value.expectedPaymentVersion = proposal.getExpectedPaymentVersion();
        value.expectedRecoveryVersion = proposal.getExpectedRecoveryVersion();
        value.requesterPlatformOperatorAccountId = proposal.getRequesterPlatformOperatorAccountId();
        value.requesterAuthorityVersion = proposal.getRequesterAuthorityVersion();
        value.approverPlatformOperatorAccountId = approval.getApproverPlatformOperatorAccountId();
        value.approverAuthorityVersion = approval.getApproverAuthorityVersion();
        value.nextAttemptAt = Objects.requireNonNull(now);
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public long claim(String owner, Instant now, Instant leaseUntil) {
        Objects.requireNonNull(now);
        if (owner == null || owner.isBlank() || owner.length() > 64
                || leaseUntil == null || !leaseUntil.isAfter(now)) conflict();
        boolean unleasedDue = (status == ExecutionStatus.PENDING || status == ExecutionStatus.VERIFYING)
                && !nextAttemptAt.isAfter(now) && leaseOwner == null;
        boolean expired = status == ExecutionStatus.PROCESSING
                && leaseExpiresAt != null && !leaseExpiresAt.isAfter(now);
        if (!unleasedDue && !expired) conflict();
        leaseOwner = owner;
        leaseToken++;
        leaseExpiresAt = leaseUntil;
        status = ExecutionStatus.PROCESSING;
        attemptCount++;
        if (operation == RecoveryAction.REQUERY_PROVIDER_RESULT) lookupAttemptCount++;
        updatedAt = now;
        return leaseToken;
    }

    public void markUnknown(String owner, long token, Instant occurredAt, Instant nextAttemptAt) {
        requireLease(owner, token, occurredAt);
        operation = RecoveryAction.REQUERY_PROVIDER_RESULT;
        status = ExecutionStatus.VERIFYING;
        clearLease();
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        updatedAt = occurredAt;
    }

    public void markSucceeded(String owner, long token, String maskedOutcome, Instant now) {
        requireLease(owner, token, now);
        if (maskedOutcome == null || !maskedOutcome.matches("^[A-Z_]{1,50}$")) conflict();
        status = ExecutionStatus.SUCCEEDED;
        this.maskedOutcome = maskedOutcome;
        completedAt = now;
        updatedAt = now;
        clearLease();
    }

    private void requireLease(String owner, long token, Instant now) {
        if (status != ExecutionStatus.PROCESSING || !Objects.equals(leaseOwner, owner)
                || leaseToken != token || leaseExpiresAt == null || leaseExpiresAt.isBefore(now)) conflict();
    }

    private void clearLease() {
        leaseOwner = null;
        leaseExpiresAt = null;
    }

    private static void conflict() {
        throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT);
    }
}
