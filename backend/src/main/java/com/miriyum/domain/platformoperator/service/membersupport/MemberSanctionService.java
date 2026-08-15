package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionApproval;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionApprovalRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSanctionService {
    private final MemberSupportCaseRepository cases;
    private final MemberSanctionRepository sanctions;
    private final MemberSanctionApprovalRepository approvals;
    private final MemberAccountSupportRegistry accounts;
    private final HighRiskCommandGuard guard;
    private final AdminCaseAssignmentManager assignments;
    private final MemberSupportAuditWriter audit;
    private final Clock clock;

    public MemberSanctionService(MemberSupportCaseRepository cases, MemberSanctionRepository sanctions,
                                 MemberSanctionApprovalRepository approvals, MemberAccountSupportRegistry accounts,
                                 HighRiskCommandGuard guard, AdminCaseAssignmentManager assignments,
                                 MemberSupportAuditWriter audit, Clock clock) {
        this.cases = cases;
        this.sanctions = sanctions;
        this.approvals = approvals;
        this.accounts = accounts;
        this.guard = guard;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public MemberSanction apply(PlatformOperatorPrincipal principal, String caseId, long expectedCaseVersion,
                                long expectedSupportVersion, MemberSanctionLevel level,
                                Set<RestrictedFeature> features, String reasonCode, String policyVersion,
                                String approval, String correlationId) {
        var supportCase = cases.findByPublicIdForUpdate(caseId)
                .filter(candidate -> candidate.getCaseType() == MemberSupportCaseType.ACCOUNT_SANCTION)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        requireAssignedVersion(supportCase.getStatus(), supportCase.getRowVersion(), expectedCaseVersion);
        validateFeatures(level, features);
        var context = guard.authorize(request(principal, supportCase.getAccountType(), supportCase.getAccountId(),
                caseId, expectedCaseVersion, PlatformOperatorPermission.ACCOUNT_SANCTION,
                AdminCommandPurpose.ACCOUNT_SANCTION, approval, correlationId));
        LocalDateTime now = LocalDateTime.now(clock);
        MemberSanction sanction = sanctions.save(MemberSanction.propose(
                supportCase, level, features, reasonCode, policyVersion, principal.accountId(), now));
        assignments.close(new AdminCaseAssignmentRequest(
                AdminCaseType.MEMBER_SUPPORT, caseId, expectedCaseVersion, principal.accountId()));
        if (level == MemberSanctionLevel.PERMANENT_SUSPENSION) {
            supportCase.pendingAdditionalApproval();
        } else {
            if (level == MemberSanctionLevel.TEMPORARY_SUSPENSION) {
                accounts.require(supportCase.getAccountType()).applySuspension(
                        supportCase.getAccountId(), expectedSupportVersion);
            } else {
                accounts.require(supportCase.getAccountType()).advanceSupportVersion(
                        supportCase.getAccountId(), expectedSupportVersion);
            }
            supportCase.decide(MemberSupportCaseStatus.APPROVED, level.name(), now);
        }
        audit.enforcement(context, level.name(), policyVersion);
        return sanction;
    }

    @Transactional
    public void approvePermanent(PlatformOperatorPrincipal principal, String sanctionId, long expectedCaseVersion,
                                 long expectedSupportVersion, String approval, String correlationId,
                                 String reasonCode) {
        MemberSanction sanction = sanctions.findByPublicIdForUpdate(sanctionId)
                .filter(candidate -> candidate.getStatus() == MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        if (sanction.getProposedByOperatorId() == principal.accountId()) {
            throw new ServiceException(AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT);
        }
        var supportCase = sanction.getSupportCase();
        if (supportCase.getRowVersion() != expectedCaseVersion
                || supportCase.getStatus() != MemberSupportCaseStatus.PENDING_ADDITIONAL_APPROVAL) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        var context = guard.authorize(request(principal, sanction.getAccountType(), sanction.getAccountId(),
                supportCase.getPublicId(), expectedCaseVersion,
                PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE,
                AdminCommandPurpose.PERMANENT_ACCOUNT_SANCTION_APPROVAL, approval, correlationId));
        if (!context.roles().contains(PlatformOperatorRole.SUPER_ADMIN)) {
            throw new ServiceException(AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        accounts.require(sanction.getAccountType()).applySuspension(sanction.getAccountId(), expectedSupportVersion);
        approvals.save(MemberSanctionApproval.record(sanction, principal.accountId(), reasonCode, now));
        sanction.approvePermanent(principal.accountId(), now);
        assignments.close(new AdminCaseAssignmentRequest(
                AdminCaseType.MEMBER_SUPPORT, supportCase.getPublicId(), expectedCaseVersion, principal.accountId()));
        supportCase.decide(MemberSupportCaseStatus.APPROVED, "PERMANENT_SUSPENSION", now);
        audit.enforcement(context, "PERMANENT_SUSPENSION_APPROVED", sanction.getPolicyVersion());
    }

    @Transactional
    public void approvePermanent(PlatformOperatorPrincipal principal, String sanctionId, long expectedCaseVersion,
                                 String approval, String correlationId, String reasonCode) {
        MemberSanction sanction = sanctions.findByPublicIdForUpdate(sanctionId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        approvePermanent(principal, sanctionId, expectedCaseVersion,
                sanction.getSupportCase().getTargetSupportVersion(), approval, correlationId, reasonCode);
    }

    private void requireAssignedVersion(MemberSupportCaseStatus status, long actual, long expected) {
        if (status != MemberSupportCaseStatus.ASSIGNED || actual != expected) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
    }

    private void validateFeatures(MemberSanctionLevel level, Set<RestrictedFeature> features) {
        if ((level == MemberSanctionLevel.FEATURE_RESTRICTION) != !features.isEmpty()) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
    }

    private HighRiskCommandRequest request(PlatformOperatorPrincipal principal, MemberAccountType accountType,
                                           long accountId, String caseId, long caseVersion,
                                           PlatformOperatorPermission permission, AdminCommandPurpose purpose,
                                           String approval, String correlationId) {
        return new HighRiskCommandRequest(principal, permission, AdminCaseType.MEMBER_SUPPORT, caseId, caseVersion,
                purpose, accountType == MemberAccountType.CONSUMER
                        ? AdminTargetType.CONSUMER_ACCOUNT : AdminTargetType.STORE_OPERATOR_ACCOUNT,
                Long.toString(accountId), approval, correlationId);
    }
}
