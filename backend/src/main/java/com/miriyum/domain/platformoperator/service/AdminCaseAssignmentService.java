package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.AdminCaseAssignmentRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class AdminCaseAssignmentService implements AdminCaseAssignmentVerifier {
    private final AdminCaseAssignmentRepository assignments;
    private final Clock clock;

    public AdminCaseAssignmentService(AdminCaseAssignmentRepository assignments, Clock clock) {
        this.assignments = assignments;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public void verify(AdminCaseAssignmentRequest request) {
        boolean valid = assignments.findByCaseTypeAndCaseIdAndCaseVersionAndPlatformOperatorAccountId(
                        request.caseType(), request.caseId(), request.caseVersion(), request.operatorId())
                .filter(assignment -> assignment.isActiveAt(clock.instant()))
                .isPresent();
        if (!valid) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }
}
