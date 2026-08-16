package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseType;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSupportAssignmentService {
    private final MemberSupportCaseRepository cases;
    private final AdminCaseAssignmentManager assignments;
    private final MemberSupportAuthorizationService authorization;
    private final MemberSupportProperties properties;
    private final Clock clock;

    public MemberSupportAssignmentService(MemberSupportCaseRepository cases,
                                          AdminCaseAssignmentManager assignments,
                                          MemberSupportAuthorizationService authorization,
                                          MemberSupportProperties properties,
                                          Clock clock) {
        this.cases = cases;
        this.assignments = assignments;
        this.authorization = authorization;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void assign(PlatformOperatorPrincipal principal, String publicCaseId) {
        MemberSupportCase supportCase = cases.findByPublicIdForUpdate(publicCaseId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        authorization.requirePermission(principal, permission(supportCase.getCaseType()));
        if (supportCase.getStatus() == MemberSupportCaseStatus.SUBMITTED) {
            supportCase.assign();
        } else if (supportCase.getStatus() != MemberSupportCaseStatus.PENDING_ADDITIONAL_APPROVAL) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        assignments.assign(new AdminCaseAssignmentCommand(
                AdminCaseType.MEMBER_SUPPORT, supportCase.getPublicId(), supportCase.getRowVersion(),
                principal.accountId(), clock.instant().plus(properties.assignmentTtl())));
    }

    private PlatformOperatorPermission permission(MemberSupportCaseType type) {
        return switch (type) {
            case ACCOUNT_RECOVERY -> PlatformOperatorPermission.MEMBER_RECOVERY;
            case ACCOUNT_SANCTION -> PlatformOperatorPermission.ACCOUNT_SANCTION;
            case ACCOUNT_APPEAL -> PlatformOperatorPermission.ACCOUNT_APPEAL_REVIEW;
        };
    }
}
