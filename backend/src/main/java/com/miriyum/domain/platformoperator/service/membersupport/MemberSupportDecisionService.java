package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.dto.membersupport.PlatformMemberSupportRequests.CaseDecisionRequest;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberAppealOutcome;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSupportDecisionService {
    private final MemberSupportCaseRepository cases;
    private final MemberRecoveryService recovery;
    private final MemberAppealService appeals;

    public MemberSupportDecisionService(MemberSupportCaseRepository cases,
                                        MemberRecoveryService recovery, MemberAppealService appeals) {
        this.cases = cases;
        this.recovery = recovery;
        this.appeals = appeals;
    }

    public void decide(PlatformOperatorPrincipal principal, String caseId, long expectedVersion,
                       String approval, String correlationId, CaseDecisionRequest request) {
        var supportCase = cases.findByPublicId(caseId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        if (supportCase.getCaseType() == MemberSupportCaseType.ACCOUNT_RECOVERY) {
            if (!request.decision().equals("APPROVE") && !request.decision().equals("REJECT")) conflict();
            recovery.decide(principal, caseId, expectedVersion, approval, correlationId,
                    request.decision().equals("APPROVE"), request.reasonCode());
            return;
        }
        if (supportCase.getCaseType() == MemberSupportCaseType.ACCOUNT_APPEAL) {
            MemberAppealOutcome outcome = switch (request.decision()) {
                case "UPHOLD" -> MemberAppealOutcome.UPHOLD;
                case "REDUCE" -> MemberAppealOutcome.REDUCE;
                case "CANCEL" -> MemberAppealOutcome.CANCEL;
                default -> throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
            };
            appeals.decide(principal, caseId, expectedVersion, supportCase.getTargetSupportVersion(),
                    outcome, request.reducedLevel(), request.restrictedFeatures(),
                    approval, correlationId, request.reasonCode());
            return;
        }
        conflict();
    }

    private static void conflict() {
        throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
    }
}
