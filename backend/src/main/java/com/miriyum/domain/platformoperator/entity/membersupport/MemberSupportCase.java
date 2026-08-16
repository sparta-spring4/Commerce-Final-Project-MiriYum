package com.miriyum.domain.platformoperator.entity.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_support_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSupportCase extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_support_case_id")
    private Long id;

    @Column(name = "case_public_id", nullable = false, length = 36, unique = true)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 30)
    private MemberSupportCaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30)
    private MemberAccountType accountType;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "identity_verification_id")
    private Long identityVerificationId;

    @Column(name = "source_sanction_id")
    private Long sourceSanctionId;

    @Column(name = "password_reset_verification_id")
    private Long passwordResetVerificationId;

    @Column(name = "password_reset_completed_at")
    private LocalDateTime passwordResetCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private MemberSupportCaseStatus status;

    @Column(name = "target_support_version", nullable = false)
    private long targetSupportVersion;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "decision_code", length = 40)
    private String decisionCode;

    @Column(name = "row_version", nullable = false)
    private long rowVersion = 1;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    public static MemberSupportCase recovery(MemberAccountType accountType, long accountId,
                                             long verificationId, long targetSupportVersion,
                                             LocalDateTime now) {
        MemberSupportCase supportCase = new MemberSupportCase();
        supportCase.publicId = UUID.randomUUID().toString();
        supportCase.caseType = MemberSupportCaseType.ACCOUNT_RECOVERY;
        supportCase.accountType = accountType;
        supportCase.accountId = accountId;
        supportCase.identityVerificationId = verificationId;
        supportCase.status = MemberSupportCaseStatus.SUBMITTED;
        supportCase.targetSupportVersion = targetSupportVersion;
        supportCase.submittedAt = now;
        return supportCase;
    }

    public static MemberSupportCase enforcement(MemberAccountType accountType, long accountId,
                                                long targetSupportVersion, String reasonCode,
                                                LocalDateTime now) {
        MemberSupportCase supportCase = new MemberSupportCase();
        supportCase.publicId = UUID.randomUUID().toString();
        supportCase.caseType = MemberSupportCaseType.ACCOUNT_SANCTION;
        supportCase.accountType = accountType;
        supportCase.accountId = accountId;
        supportCase.status = MemberSupportCaseStatus.SUBMITTED;
        supportCase.targetSupportVersion = targetSupportVersion;
        supportCase.reasonCode = reasonCode;
        supportCase.submittedAt = now;
        return supportCase;
    }

    public static MemberSupportCase appeal(MemberAccountType accountType, long accountId,
                                           long sourceSanctionId, long targetSupportVersion,
                                           LocalDateTime now) {
        return appeal(accountType, accountId, sourceSanctionId, null, targetSupportVersion, now);
    }

    public static MemberSupportCase appeal(MemberAccountType accountType, long accountId,
                                           long sourceSanctionId, Long identityVerificationId,
                                           long targetSupportVersion, LocalDateTime now) {
        MemberSupportCase supportCase = new MemberSupportCase();
        supportCase.publicId = UUID.randomUUID().toString();
        supportCase.caseType = MemberSupportCaseType.ACCOUNT_APPEAL;
        supportCase.accountType = accountType;
        supportCase.accountId = accountId;
        supportCase.sourceSanctionId = sourceSanctionId;
        supportCase.identityVerificationId = identityVerificationId;
        supportCase.status = MemberSupportCaseStatus.SUBMITTED;
        supportCase.targetSupportVersion = targetSupportVersion;
        supportCase.reasonCode = "MEMBER_APPEAL";
        supportCase.submittedAt = now;
        return supportCase;
    }

    public void assign() {
        requireStatus(MemberSupportCaseStatus.SUBMITTED);
        status = MemberSupportCaseStatus.ASSIGNED;
        rowVersion++;
    }

    public void decide(MemberSupportCaseStatus terminalStatus, String decisionCode, LocalDateTime now) {
        if (status != MemberSupportCaseStatus.ASSIGNED
                && status != MemberSupportCaseStatus.PENDING_ADDITIONAL_APPROVAL) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        if (terminalStatus != MemberSupportCaseStatus.APPROVED
                && terminalStatus != MemberSupportCaseStatus.REJECTED
                && terminalStatus != MemberSupportCaseStatus.UPHELD
                && terminalStatus != MemberSupportCaseStatus.REDUCED
                && terminalStatus != MemberSupportCaseStatus.CANCELLED) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        status = terminalStatus;
        this.decisionCode = decisionCode;
        decidedAt = now;
        rowVersion++;
    }

    public void pendingAdditionalApproval() {
        requireStatus(MemberSupportCaseStatus.ASSIGNED);
        status = MemberSupportCaseStatus.PENDING_ADDITIONAL_APPROVAL;
        rowVersion++;
    }

    public void issuePasswordResetVerification(long verificationId) {
        if (caseType != MemberSupportCaseType.ACCOUNT_RECOVERY
                || status != MemberSupportCaseStatus.APPROVED
                || passwordResetVerificationId != null
                || passwordResetCompletedAt != null || verificationId < 1) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        passwordResetVerificationId = verificationId;
        rowVersion++;
    }

    public void completePasswordReset(LocalDateTime now) {
        if (caseType != MemberSupportCaseType.ACCOUNT_RECOVERY
                || status != MemberSupportCaseStatus.APPROVED
                || passwordResetVerificationId == null || passwordResetCompletedAt != null) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        passwordResetCompletedAt = now;
        rowVersion++;
    }

    private void requireStatus(MemberSupportCaseStatus expected) {
        if (status != expected) throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
    }
}
