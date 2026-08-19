package com.miriyum.domain.platformoperator.paymentrecovery.entity;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
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
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "payment_recovery_proposals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRecoveryProposal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_recovery_proposal_id")
    private Long id;
    @Column(name = "case_public_id", nullable = false, length = 36)
    private String casePublicId;
    @Column(name = "proposal_version", nullable = false)
    private long proposalVersion;
    @Column(name = "expected_case_version", nullable = false)
    private long expectedCaseVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RecoveryAction action;
    @Column(name = "requested_amount_minor", nullable = false)
    private long requestedAmountMinor;
    @Column(name = "cumulative_lineage_amount_minor", nullable = false)
    private long cumulativeLineageAmountMinor;
    @Column(name = "original_amount_minor", nullable = false)
    private long originalAmountMinor;
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_tier", nullable = false, length = 40)
    private ApprovalTier approvalTier;
    @Column(name = "expected_handoff_version", nullable = false)
    private long expectedHandoffVersion;
    @Column(name = "expected_payment_version", nullable = false)
    private long expectedPaymentVersion;
    @Column(name = "expected_recovery_version", nullable = false)
    private long expectedRecoveryVersion;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "requester_platform_operator_account_id", nullable = false)
    private long requesterPlatformOperatorAccountId;
    @Column(name = "requester_authority_version", nullable = false)
    private long requesterAuthorityVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requester_roles", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorRole> requesterRoles;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requester_permissions", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorPermission> requesterPermissions;
    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static PaymentRecoveryProposal propose(
            String casePublicId, long proposalVersion, long expectedCaseVersion, RecoveryAction action,
            long requestedAmountMinor, long cumulativeLineageAmountMinor,
            long originalAmountMinor, String currency,
            long expectedHandoffVersion, long expectedPaymentVersion,
            long expectedRecoveryVersion, String requestFingerprint,
            long requesterId, long requesterAuthorityVersion,
            Set<PlatformOperatorRole> requesterRoles,
            Set<PlatformOperatorPermission> requesterPermissions,
            String idempotencyKey, Instant now) {
        if (action != RecoveryAction.RETRY_REFUND) {
            throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_ACTION_NOT_ALLOWED);
        }
        if (requestedAmountMinor <= 0 || cumulativeLineageAmountMinor <= 0
                || originalAmountMinor <= 0) {
            throw new IllegalArgumentException("amounts must be positive");
        }
        if (cumulativeLineageAmountMinor > originalAmountMinor) {
            throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_COMPENSATION_NOT_SUPPORTED);
        }
        PaymentRecoveryProposal value = new PaymentRecoveryProposal();
        value.casePublicId = requireUuid(casePublicId);
        if (proposalVersion < 1 || expectedCaseVersion < 1 || expectedHandoffVersion < 0
                || expectedPaymentVersion < 0 || expectedRecoveryVersion < 0
                || requesterAuthorityVersion < 1) {
            throw new IllegalArgumentException("versions are invalid");
        }
        value.proposalVersion = proposalVersion;
        value.expectedCaseVersion = expectedCaseVersion;
        value.action = action;
        value.requestedAmountMinor = requestedAmountMinor;
        value.cumulativeLineageAmountMinor = cumulativeLineageAmountMinor;
        value.originalAmountMinor = originalAmountMinor;
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency is invalid");
        }
        value.currency = currency;
        value.approvalTier = cumulativeLineageAmountMinor >= 200_001L
                ? ApprovalTier.ADDITIONAL_SUPER_ADMIN : ApprovalTier.SINGLE_OPERATOR;
        value.expectedHandoffVersion = expectedHandoffVersion;
        value.expectedPaymentVersion = expectedPaymentVersion;
        value.expectedRecoveryVersion = expectedRecoveryVersion;
        if (requestFingerprint == null || !requestFingerprint.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("requestFingerprint is invalid");
        }
        value.requestFingerprint = requestFingerprint;
        value.requesterPlatformOperatorAccountId = requesterId;
        value.requesterAuthorityVersion = requesterAuthorityVersion;
        value.requesterRoles = Set.copyOf(Objects.requireNonNull(requesterRoles));
        value.requesterPermissions = Set.copyOf(Objects.requireNonNull(requesterPermissions));
        value.idempotencyKey = requireUuid(idempotencyKey);
        value.createdAt = Objects.requireNonNull(now);
        return value;
    }

    private static String requireUuid(String value) {
        try {
            return java.util.UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("UUID is invalid", exception);
        }
    }
}
