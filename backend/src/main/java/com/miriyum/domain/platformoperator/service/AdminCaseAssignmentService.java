package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.AdminCaseAssignmentRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class AdminCaseAssignmentService implements AdminCaseAssignmentVerifier, AdminCaseAssignmentManager {
    private final AdminCaseAssignmentRepository assignments;
    private final Clock clock;

    public AdminCaseAssignmentService(AdminCaseAssignmentRepository assignments, Clock clock) {
        this.assignments = assignments;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void verify(AdminCaseAssignmentRequest request) {
        boolean valid = assignments.findByCaseForUpdate(request.caseType(), request.caseId(), request.caseVersion())
                .filter(assignment -> assignment.getPlatformOperatorAccountId() == request.operatorId())
                .filter(assignment -> assignment.isActiveAt(clock.instant()))
                .isPresent();
        if (!valid) {
            throw new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED);
        }
    }

    @Override
    @Transactional
    public void assign(AdminCaseAssignmentCommand command) {
        if (!command.expiresAt().isAfter(clock.instant())
                || assignments.findByCaseForUpdate(command.caseType(), command.caseId(), command.caseVersion())
                .isPresent()) {
            throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }
        try {
            assignments.saveAndFlush(AdminCaseAssignment.assign(
                    command.caseType(), command.caseId(), command.caseVersion(), command.operatorId(),
                    command.expiresAt(), clock.instant()));
        } catch (DataIntegrityViolationException exception) {
            throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    @Override
    @Transactional
    public void reassign(AdminCaseAssignmentRequest currentAssignment, long nextOperatorId, Instant expiresAt) {
        AdminCaseAssignment assignment = requireCurrent(currentAssignment);
        assignment.reassign(nextOperatorId, expiresAt, clock.instant());
    }

    @Override
    @Transactional
    public void close(AdminCaseAssignmentRequest currentAssignment) {
        requireCurrent(currentAssignment).close();
    }

    private AdminCaseAssignment requireCurrent(AdminCaseAssignmentRequest request) {
        return assignments.findByCaseForUpdate(request.caseType(), request.caseId(), request.caseVersion())
                .filter(assignment -> assignment.getPlatformOperatorAccountId() == request.operatorId())
                .filter(assignment -> assignment.isActiveAt(clock.instant()))
                .orElseThrow(() -> new ServiceException(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
    }
}
