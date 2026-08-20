package com.miriyum.domain.platformoperator.adminmonitoring.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentVerifier;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.admin-monitoring", name = "enabled", havingValue = "true")
public class AdminMonitoringAuthorizationService {

    private final OperatorAuthorityReader authorities;
    private final AdminCaseAssignmentVerifier assignments;

    public AdminMonitoringAuthorizationService(
            OperatorAuthorityReader authorities,
            AdminCaseAssignmentVerifier assignments
    ) {
        this.authorities = authorities;
        this.assignments = assignments;
    }

    public void requireRead(PlatformOperatorPrincipal principal) {
        var authority = authorities.requireCurrentAuthority(
                principal.accountId(), principal.authorityVersion());
        if (!authority.permissions().contains(PlatformOperatorPermission.OPERATIONS_MONITOR_READ)) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }

    @Transactional
    public void requireDetail(
            PlatformOperatorPrincipal principal,
            String caseId,
            long caseVersion
    ) {
        requireRead(principal);
        assignments.verify(new AdminCaseAssignmentRequest(
                AdminCaseType.OPERATIONS_MONITORING,
                caseId,
                caseVersion,
                principal.accountId()));
    }
}
