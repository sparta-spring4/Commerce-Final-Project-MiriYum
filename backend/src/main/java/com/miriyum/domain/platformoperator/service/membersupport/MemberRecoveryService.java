package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberIdentityVerificationRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberRecoveryService {
    private final MemberSupportCaseRepository cases;
    private final MemberIdentityVerificationRepository verifications;
    private final MemberSupportCrypto crypto;
    private final MemberAccountSupportRegistry accounts;
    private final HighRiskCommandGuard guard;
    private final AdminCaseAssignmentManager assignments;
    private final MemberSupportAuditWriter audit;
    private final Clock clock;

    public MemberRecoveryService(MemberSupportCaseRepository cases,
                                 MemberIdentityVerificationRepository verifications,
                                 MemberSupportCrypto crypto,
                                 MemberAccountSupportRegistry accounts,
                                 HighRiskCommandGuard guard,
                                 AdminCaseAssignmentManager assignments,
                                 MemberSupportAuditWriter audit,
                                 Clock clock) {
        this.cases = cases;
        this.verifications = verifications;
        this.crypto = crypto;
        this.accounts = accounts;
        this.guard = guard;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void decide(PlatformOperatorPrincipal principal, String caseId, long expectedCaseVersion,
                       String approval, String correlationId, boolean approved, String decisionCode) {
        var supportCase = cases.findByPublicIdForUpdate(caseId)
                .filter(candidate -> candidate.getCaseType() == MemberSupportCaseType.ACCOUNT_RECOVERY)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        if (supportCase.getStatus() != MemberSupportCaseStatus.ASSIGNED
                || supportCase.getRowVersion() != expectedCaseVersion) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        AdminTargetType targetType = supportCase.getAccountType() == MemberAccountType.CONSUMER
                ? AdminTargetType.CONSUMER_ACCOUNT : AdminTargetType.STORE_OPERATOR_ACCOUNT;
        var context = guard.authorize(new HighRiskCommandRequest(
                principal, PlatformOperatorPermission.MEMBER_RECOVERY, AdminCaseType.MEMBER_SUPPORT,
                caseId, expectedCaseVersion, AdminCommandPurpose.MEMBER_RECOVERY, targetType,
                Long.toString(supportCase.getAccountId()), approval, correlationId));
        if (approved) {
            var verification = verifications.findById(supportCase.getIdentityVerificationId())
                    .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT));
            accounts.require(supportCase.getAccountType()).approveRecovery(
                    supportCase.getAccountId(), supportCase.getTargetSupportVersion(),
                    crypto.decrypt(verification.getEncryptedNewEmail()));
        }
        assignments.close(new AdminCaseAssignmentRequest(
                AdminCaseType.MEMBER_SUPPORT, caseId, expectedCaseVersion, principal.accountId()));
        supportCase.decide(approved ? MemberSupportCaseStatus.APPROVED : MemberSupportCaseStatus.REJECTED,
                decisionCode, LocalDateTime.now(clock));
        audit.recovery(context, decisionCode);
    }

    @Transactional
    public void completePasswordReset(String rawProof, MemberAccountType accountType, String newPassword) {
        if (rawProof == null || rawProof.isBlank()) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND);
        }
        var verification = verifications.findByProofDigest(crypto.digest(rawProof))
                .filter(candidate -> candidate.getAccountType() == accountType)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        var supportCase = cases.findByIdentityVerificationIdAndStatus(
                        verification.getId(), MemberSupportCaseStatus.APPROVED)
                .filter(candidate -> candidate.getAccountType() == accountType)
                .filter(candidate -> candidate.getAccountId() == verification.getAccountId())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        accounts.require(accountType).replaceRecoveredPassword(supportCase.getAccountId(), newPassword);
    }
}
