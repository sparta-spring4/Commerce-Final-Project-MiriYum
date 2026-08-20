package com.miriyum.domain.platformoperator.paymentrecovery.entity;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ApprovalTier;
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
@Table(name = "payment_recovery_approvals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRecoveryApproval {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_recovery_approval_id")
    private Long id;
    @Column(name = "case_public_id", nullable = false, length = 36)
    private String casePublicId;
    @Column(name = "proposal_version", nullable = false)
    private long proposalVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_tier", nullable = false, length = 40)
    private ApprovalTier approvalTier;
    @Column(name = "requester_platform_operator_account_id", nullable = false)
    private long requesterPlatformOperatorAccountId;
    @Column(name = "approver_platform_operator_account_id", nullable = false)
    private long approverPlatformOperatorAccountId;
    @Column(name = "approver_authority_version", nullable = false)
    private long approverAuthorityVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approver_roles", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorRole> approverRoles;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approver_permissions", nullable = false, columnDefinition = "json")
    private Set<PlatformOperatorPermission> approverPermissions;
    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;
    @Column(name = "approved_at", nullable = false, updatable = false)
    private Instant approvedAt;

    public static PaymentRecoveryApproval approve(
            PaymentRecoveryProposal proposal, long approverId, long approverAuthorityVersion,
            Set<PlatformOperatorRole> approverRoles,
            Set<PlatformOperatorPermission> approverPermissions,
            String idempotencyKey, Instant now) {
        Objects.requireNonNull(proposal);
        Set<PlatformOperatorRole> roles = Set.copyOf(Objects.requireNonNull(approverRoles));
        Set<PlatformOperatorPermission> permissions = Set.copyOf(
                Objects.requireNonNull(approverPermissions));
        if (approverAuthorityVersion < 1) throw new IllegalArgumentException("authority version is invalid");
        if (proposal.getApprovalTier() == ApprovalTier.SINGLE_OPERATOR) {
            if (approverId != proposal.getRequesterPlatformOperatorAccountId()
                    || !permissions.contains(PlatformOperatorPermission.PAYMENT_RECOVERY_EXECUTE)) {
                throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_ACTION_NOT_ALLOWED);
            }
        } else {
            if (approverId == proposal.getRequesterPlatformOperatorAccountId()) {
                throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_APPROVER_MUST_DIFFER);
            }
            if (!roles.contains(PlatformOperatorRole.SUPER_ADMIN)
                    || !permissions.contains(PlatformOperatorPermission.PAYMENT_RECOVERY_HIGH_VALUE_APPROVE)) {
                throw new ServiceException(PaymentRecoveryErrorCode.RECOVERY_ACTION_NOT_ALLOWED);
            }
        }
        PaymentRecoveryApproval value = new PaymentRecoveryApproval();
        value.casePublicId = proposal.getCasePublicId();
        value.proposalVersion = proposal.getProposalVersion();
        value.approvalTier = proposal.getApprovalTier();
        value.requesterPlatformOperatorAccountId = proposal.getRequesterPlatformOperatorAccountId();
        value.approverPlatformOperatorAccountId = approverId;
        value.approverAuthorityVersion = approverAuthorityVersion;
        value.approverRoles = roles;
        value.approverPermissions = permissions;
        value.idempotencyKey = java.util.UUID.fromString(idempotencyKey).toString();
        value.approvedAt = Objects.requireNonNull(now);
        return value;
    }
}
