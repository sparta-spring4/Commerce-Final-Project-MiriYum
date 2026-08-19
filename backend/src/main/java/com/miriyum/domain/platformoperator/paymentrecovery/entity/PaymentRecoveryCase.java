package com.miriyum.domain.platformoperator.paymentrecovery.entity;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
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
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_recovery_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRecoveryCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_recovery_case_id")
    private Long id;

    @Column(name = "case_public_id", nullable = false, unique = true, length = 36)
    private String publicId;

    @Column(name = "handoff_id", nullable = false, unique = true, length = 19)
    private String handoffId;

    @Column(name = "lineage_id", nullable = false, length = 36)
    private String lineageId;

    @Column(name = "case_sequence", nullable = false)
    private long caseSequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaseStatus status;

    @Column(name = "case_version", nullable = false)
    private long caseVersion;

    @Column(name = "current_proposal_version", nullable = false)
    private long currentProposalVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_kind", nullable = false, length = 40)
    private RecoveryKind recoveryKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 20)
    private ResultStatus resultStatus;

    @Column(name = "original_amount_minor", nullable = false)
    private long originalAmountMinor;

    @Column(name = "cumulative_refunded_amount_minor", nullable = false)
    private long cumulativeRefundedAmountMinor;

    @Column(name = "remaining_refundable_amount_minor", nullable = false)
    private long remainingRefundableAmountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "masked_provider_reference", length = 20)
    private String maskedProviderReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_actions", nullable = false, columnDefinition = "json")
    private Set<RecoveryAction> allowedActions;

    @Column(name = "handoff_version", nullable = false)
    private long handoffVersion;

    @Column(name = "payment_version", nullable = false)
    private long paymentVersion;

    @Column(name = "recovery_version", nullable = false)
    private long recoveryVersion;

    @Column(name = "active_lineage_key", insertable = false, updatable = false, length = 36)
    private String activeLineageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public static PaymentRecoveryCase open(
            String handoffId,
            RecoveryKind kind,
            ResultStatus resultStatus,
            long originalAmountMinor,
            long cumulativeRefundedAmountMinor,
            long remainingRefundableAmountMinor,
            String currency,
            Set<RecoveryAction> allowedActions,
            String maskedProviderReference,
            long handoffVersion,
            long paymentVersion,
            long recoveryVersion,
            Instant now
    ) {
        PaymentRecoveryCase value = new PaymentRecoveryCase();
        value.publicId = UUID.randomUUID().toString();
        value.handoffId = requirePublicId(handoffId);
        value.lineageId = UUID.randomUUID().toString();
        value.activeLineageKey = value.lineageId;
        value.caseSequence = 1L;
        value.status = CaseStatus.RECONCILIATION_PENDING;
        value.caseVersion = 1L;
        value.currentProposalVersion = 0L;
        value.recoveryKind = Objects.requireNonNull(kind);
        value.resultStatus = Objects.requireNonNull(resultStatus);
        value.originalAmountMinor = requirePositive(originalAmountMinor, "originalAmountMinor");
        value.cumulativeRefundedAmountMinor = requireNonNegative(
                cumulativeRefundedAmountMinor, "cumulativeRefundedAmountMinor");
        value.remainingRefundableAmountMinor = requireNonNegative(
                remainingRefundableAmountMinor, "remainingRefundableAmountMinor");
        if (cumulativeRefundedAmountMinor + remainingRefundableAmountMinor > originalAmountMinor) {
            throw new IllegalArgumentException("refund amounts exceed original amount");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency is invalid");
        }
        value.currency = currency;
        value.allowedActions = Set.copyOf(Objects.requireNonNull(allowedActions));
        value.maskedProviderReference = maskedProviderReference;
        value.handoffVersion = requireNonNegative(handoffVersion, "handoffVersion");
        value.paymentVersion = requireNonNegative(paymentVersion, "paymentVersion");
        value.recoveryVersion = requireNonNegative(recoveryVersion, "recoveryVersion");
        value.createdAt = Objects.requireNonNull(now);
        value.updatedAt = now;
        return value;
    }

    public void beginInvestigation(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.RECONCILIATION_PENDING),
                CaseStatus.INVESTIGATING, now);
    }

    public void recordProposal(
            long expectedVersion,
            long proposalVersion,
            ApprovalTier tier,
            Instant now
    ) {
        require(expectedVersion, Set.of(CaseStatus.INVESTIGATING, CaseStatus.FAILED));
        if (proposalVersion != currentProposalVersion + 1L) conflict();
        currentProposalVersion = proposalVersion;
        status = tier == ApprovalTier.ADDITIONAL_SUPER_ADMIN
                ? CaseStatus.ADDITIONAL_APPROVAL_PENDING
                : CaseStatus.PROPOSED;
        advance(now);
    }

    public void recordAdditionalApproval(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.ADDITIONAL_APPROVAL_PENDING),
                CaseStatus.PROPOSED, now);
    }

    public void queueExecution(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.PROPOSED), CaseStatus.EXECUTING, now);
    }

    public void startVerification(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.EXECUTING), CaseStatus.VERIFYING, now);
    }

    public void complete(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.VERIFYING), CaseStatus.COMPLETED, now);
        activeLineageKey = null;
    }

    public void hold(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.VERIFYING), CaseStatus.HOLD, now);
    }

    public void holdFromInvestigation(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.INVESTIGATING, CaseStatus.EXECUTING),
                CaseStatus.HOLD, now);
    }

    public void fail(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.EXECUTING, CaseStatus.VERIFYING),
                CaseStatus.FAILED, now);
    }

    public void resumeInvestigation(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.HOLD, CaseStatus.FAILED),
                CaseStatus.INVESTIGATING, now);
    }

    public void closeUnresolved(long expectedVersion, Instant now) {
        transition(expectedVersion, Set.of(CaseStatus.HOLD), CaseStatus.FAILED_UNRESOLVED, now);
        activeLineageKey = null;
    }

    private void transition(
            long expectedVersion,
            Set<CaseStatus> expectedStatuses,
            CaseStatus next,
            Instant now
    ) {
        require(expectedVersion, expectedStatuses);
        status = next;
        advance(now);
    }

    private void require(long expectedVersion, Set<CaseStatus> expectedStatuses) {
        if (caseVersion != expectedVersion || !expectedStatuses.contains(status)) conflict();
    }

    private void advance(Instant now) {
        caseVersion++;
        updatedAt = Objects.requireNonNull(now);
    }

    private static void conflict() {
        throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_CASE_STATE_CONFLICT);
    }

    private static String requirePublicId(String value) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalArgumentException("handoffId is invalid");
        }
        return value;
    }

    private static long requirePositive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static long requireNonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }
}
