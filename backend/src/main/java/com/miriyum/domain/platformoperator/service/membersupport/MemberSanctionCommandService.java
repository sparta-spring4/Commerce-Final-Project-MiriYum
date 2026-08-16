package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
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
public class MemberSanctionCommandService {
    private final MemberSupportAuthorizationService authorization;
    private final MemberAccountSupportRegistry accounts;
    private final MemberSupportCaseRepository cases;
    private final AdminCaseAssignmentManager assignments;
    private final MemberSupportProperties properties;
    private final MemberSanctionService sanctions;
    private final Clock clock;

    public MemberSanctionCommandService(MemberSupportAuthorizationService authorization,
                                        MemberAccountSupportRegistry accounts,
                                        MemberSupportCaseRepository cases,
                                        AdminCaseAssignmentManager assignments,
                                        MemberSupportProperties properties,
                                        MemberSanctionService sanctions, Clock clock) {
        this.authorization = authorization;
        this.accounts = accounts;
        this.cases = cases;
        this.assignments = assignments;
        this.properties = properties;
        this.sanctions = sanctions;
        this.clock = clock;
    }

    @Transactional
    public MemberSanction createAndApply(PlatformOperatorPrincipal principal,
                                         MemberAccountType accountType, long accountId,
                                         long expectedSupportVersion, MemberSanctionLevel level,
                                         Set<RestrictedFeature> features, String reasonCode, String policyVersion,
                                         String approval, String correlationId) {
        authorization.requirePermission(principal, PlatformOperatorPermission.ACCOUNT_SANCTION);
        var target = accounts.require(accountType).findMinimal(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        if (target.supportVersion() != expectedSupportVersion) {
            throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        MemberSupportCase supportCase = cases.save(MemberSupportCase.enforcement(
                accountType, accountId, expectedSupportVersion, reasonCode, LocalDateTime.now(clock)));
        supportCase.assign();
        assignments.assign(new AdminCaseAssignmentCommand(
                AdminCaseType.MEMBER_SUPPORT, supportCase.getPublicId(), supportCase.getRowVersion(),
                principal.accountId(), clock.instant().plus(properties.assignmentTtl())));
        return sanctions.apply(principal, supportCase.getPublicId(), supportCase.getRowVersion(),
                expectedSupportVersion, level, features, reasonCode, policyVersion, approval, correlationId);
    }
}
