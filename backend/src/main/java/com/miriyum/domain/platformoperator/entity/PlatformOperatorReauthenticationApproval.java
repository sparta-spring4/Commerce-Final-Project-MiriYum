package com.miriyum.domain.platformoperator.entity;

import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "platform_operator_reauthentication_approvals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformOperatorReauthenticationApproval {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "platform_operator_reauthentication_approval_id")
    private Long id;

    @Column(name = "approval_digest", nullable = false, unique = true, length = 64)
    private String approvalDigest;

    @Column(name = "platform_operator_account_id", nullable = false)
    private Long platformOperatorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AdminCommandPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50)
    private AdminTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Column(name = "session_fingerprint", nullable = false, length = 64)
    private String sessionFingerprint;

    @Column(name = "authority_version", nullable = false)
    private long authorityVersion;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    public static PlatformOperatorReauthenticationApproval issue(
            String approvalDigest,
            Long operatorId,
            AdminCommandPurpose purpose,
            AdminTargetType targetType,
            String targetId,
            String sessionFingerprint,
            long authorityVersion,
            Instant issuedAt,
            Instant expiresAt
    ) {
        PlatformOperatorReauthenticationApproval approval = new PlatformOperatorReauthenticationApproval();
        approval.approvalDigest = requireText(approvalDigest);
        approval.platformOperatorAccountId = Objects.requireNonNull(operatorId);
        approval.purpose = Objects.requireNonNull(purpose);
        approval.targetType = Objects.requireNonNull(targetType);
        approval.targetId = requireText(targetId);
        approval.sessionFingerprint = requireText(sessionFingerprint);
        approval.authorityVersion = authorityVersion;
        approval.issuedAt = Objects.requireNonNull(issuedAt);
        approval.expiresAt = Objects.requireNonNull(expiresAt);
        if (authorityVersion < 1 || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("positive version and future expiry are required");
        }
        return approval;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("approval binding text is required");
        }
        return value;
    }
}
