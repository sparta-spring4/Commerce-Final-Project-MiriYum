package com.miriyum.domain.platformoperator.entity.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_identity_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberIdentityVerification extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_identity_verification_id")
    private Long id;

    @Column(name = "proof_digest", nullable = false, length = 64, unique = true)
    private String proofDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30)
    private MemberAccountType accountType;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private MemberVerificationPurpose purpose;

    @Column(name = "encrypted_new_email", columnDefinition = "VARBINARY(1024)")
    private byte[] encryptedNewEmail;

    @Column(name = "new_email_digest", length = 64)
    private String newEmailDigest;

    @Column(name = "source_sanction_id")
    private Long sourceSanctionId;

    @Column(name = "evidence_verified", nullable = false)
    private boolean evidenceVerified;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public static MemberIdentityVerification recovery(
            String proofDigest, MemberAccountType accountType, long accountId,
            byte[] encryptedNewEmail, String newEmailDigest,
            LocalDateTime issuedAt, LocalDateTime expiresAt
    ) {
        MemberIdentityVerification verification = new MemberIdentityVerification();
        verification.proofDigest = proofDigest;
        verification.accountType = accountType;
        verification.accountId = accountId;
        verification.purpose = MemberVerificationPurpose.MEMBER_RECOVERY;
        verification.encryptedNewEmail = encryptedNewEmail.clone();
        verification.newEmailDigest = newEmailDigest;
        verification.evidenceVerified = true;
        verification.issuedAt = issuedAt;
        verification.expiresAt = expiresAt;
        return verification;
    }

    public static MemberIdentityVerification passwordReset(
            String proofDigest, MemberAccountType accountType, long accountId,
            LocalDateTime issuedAt, LocalDateTime expiresAt
    ) {
        MemberIdentityVerification verification = new MemberIdentityVerification();
        verification.proofDigest = proofDigest;
        verification.accountType = accountType;
        verification.accountId = accountId;
        verification.purpose = MemberVerificationPurpose.PASSWORD_RESET;
        verification.evidenceVerified = true;
        verification.issuedAt = issuedAt;
        verification.expiresAt = expiresAt;
        return verification;
    }

    public static MemberIdentityVerification appeal(
            String proofDigest, MemberAccountType accountType, long accountId, long sanctionId,
            LocalDateTime issuedAt, LocalDateTime expiresAt) {
        MemberIdentityVerification verification = new MemberIdentityVerification();
        verification.proofDigest = proofDigest;
        verification.accountType = accountType;
        verification.accountId = accountId;
        verification.purpose = MemberVerificationPurpose.ACCOUNT_APPEAL;
        verification.sourceSanctionId = sanctionId;
        verification.evidenceVerified = true;
        verification.issuedAt = issuedAt;
        verification.expiresAt = expiresAt;
        return verification;
    }

    public boolean consume(MemberAccountType expectedType, MemberVerificationPurpose expectedPurpose, LocalDateTime now) {
        if (!evidenceVerified || consumedAt != null || !expiresAt.isAfter(now)
                || accountType != expectedType || purpose != expectedPurpose) {
            return false;
        }
        consumedAt = now;
        return true;
    }
}
