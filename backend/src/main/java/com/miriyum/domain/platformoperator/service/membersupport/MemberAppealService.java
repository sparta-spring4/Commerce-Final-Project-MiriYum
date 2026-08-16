package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.HighRiskCommandRequest;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberAppealOutcome;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
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
public class MemberAppealService {
    private final MemberSupportCaseRepository cases;
    private final MemberSanctionRepository sanctions;
    private final MemberAccountSupportRegistry accounts;
    private final HighRiskCommandGuard guard;
    private final AdminCaseAssignmentManager assignments;
    private final MemberSupportAuditWriter audit;
    private final Clock clock;

    public MemberAppealService(MemberSupportCaseRepository cases, MemberSanctionRepository sanctions,
                               MemberAccountSupportRegistry accounts, HighRiskCommandGuard guard,
                               AdminCaseAssignmentManager assignments, MemberSupportAuditWriter audit,
                               Clock clock) {
        this.cases = cases;
        this.sanctions = sanctions;
        this.accounts = accounts;
        this.guard = guard;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void decide(PlatformOperatorPrincipal principal, String caseId, long expectedCaseVersion,
                       long expectedSupportVersion, MemberAppealOutcome outcome,
                       MemberSanctionLevel reducedLevel, Set<RestrictedFeature> features,
                       String approval, String correlationId, String reasonCode) {
        var appeal = cases.findByPublicIdForUpdate(caseId)
                .filter(candidate -> candidate.getCaseType() == MemberSupportCaseType.ACCOUNT_APPEAL)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        if (appeal.getStatus() != MemberSupportCaseStatus.ASSIGNED
                || appeal.getRowVersion() != expectedCaseVersion) conflict();
        MemberSanction original = sanctions.findByIdForUpdate(appeal.getSourceSanctionId())
                .filter(candidate -> candidate.getStatus() == MemberSanctionStatus.APPLIED)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT));
        var context = guard.authorize(new HighRiskCommandRequest(
                principal, PlatformOperatorPermission.ACCOUNT_APPEAL_REVIEW, AdminCaseType.MEMBER_SUPPORT,
                caseId, expectedCaseVersion, AdminCommandPurpose.ACCOUNT_APPEAL_DECISION,
                appeal.getAccountType() == MemberAccountType.CONSUMER
                        ? AdminTargetType.CONSUMER_ACCOUNT : AdminTargetType.STORE_OPERATOR_ACCOUNT,
                Long.toString(appeal.getAccountId()), approval, correlationId));
        if (original.getLevel() == MemberSanctionLevel.PERMANENT_SUSPENSION
                && !context.roles().contains(PlatformOperatorRole.SUPER_ADMIN)) {
            throw new ServiceException(AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        switch (outcome) {
            case UPHOLD -> {
                accounts.require(appeal.getAccountType()).advanceSupportVersion(
                        appeal.getAccountId(), expectedSupportVersion);
                appeal.decide(MemberSupportCaseStatus.UPHELD, reasonCode, now);
            }
            case REDUCE -> reduce(principal, appeal, original, expectedSupportVersion,
                    reducedLevel, features, reasonCode, now);
            case CANCEL -> cancel(principal, appeal, original, expectedSupportVersion, reasonCode, now);
        }
        assignments.close(new AdminCaseAssignmentRequest(
                AdminCaseType.MEMBER_SUPPORT, caseId, expectedCaseVersion, principal.accountId()));
        audit.enforcement(context, outcome.name(), original.getPolicyVersion());
    }

    private void reduce(PlatformOperatorPrincipal principal,
                        com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase appeal,
                        MemberSanction original, long expectedSupportVersion,
                        MemberSanctionLevel reducedLevel, Set<RestrictedFeature> features,
                        String reasonCode, LocalDateTime now) {
        if (reducedLevel == null || rank(reducedLevel) >= rank(original.getLevel())
                || (reducedLevel == MemberSanctionLevel.FEATURE_RESTRICTION) != !features.isEmpty()) conflict();
        original.markReduced();
        sanctions.save(MemberSanction.reducedRevision(
                appeal, original, reducedLevel, features, reasonCode, principal.accountId(), now));
        if (isSuspension(original.getLevel()) && !isSuspension(reducedLevel)) {
            retireSuspension(appeal, original, expectedSupportVersion, now);
        } else {
            accounts.require(appeal.getAccountType()).advanceSupportVersion(
                    appeal.getAccountId(), expectedSupportVersion);
        }
        appeal.decide(MemberSupportCaseStatus.REDUCED, reasonCode, now);
    }

    private void cancel(PlatformOperatorPrincipal principal,
                        com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase appeal,
                        MemberSanction original, long expectedSupportVersion,
                        String reasonCode, LocalDateTime now) {
        original.markCancelled();
        sanctions.save(MemberSanction.cancelledRevision(appeal, original, reasonCode, principal.accountId(), now));
        if (isSuspension(original.getLevel())) {
            retireSuspension(appeal, original, expectedSupportVersion, now);
        } else {
            accounts.require(appeal.getAccountType()).advanceSupportVersion(
                    appeal.getAccountId(), expectedSupportVersion);
        }
        appeal.decide(MemberSupportCaseStatus.CANCELLED, reasonCode, now);
    }

    private void retireSuspension(
            com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase appeal,
            MemberSanction original, long expectedSupportVersion, LocalDateTime now) {
        var account = accounts.require(appeal.getAccountType());
        if (sanctions.countOtherActiveSuspensions(
                appeal.getAccountType(), appeal.getAccountId(), original.getId(), now) > 0) {
            account.advanceSupportVersion(appeal.getAccountId(), expectedSupportVersion);
            return;
        }
        account.clearSuspension(appeal.getAccountId(), expectedSupportVersion);
    }

    private boolean isSuspension(MemberSanctionLevel level) {
        return level == MemberSanctionLevel.TEMPORARY_SUSPENSION
                || level == MemberSanctionLevel.PERMANENT_SUSPENSION;
    }

    private int rank(MemberSanctionLevel level) {
        return switch (level) {
            case WARNING -> 0;
            case FEATURE_RESTRICTION -> 1;
            case TEMPORARY_SUSPENSION -> 2;
            case PERMANENT_SUSPENSION -> 3;
        };
    }

    private static void conflict() {
        throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
    }
}
