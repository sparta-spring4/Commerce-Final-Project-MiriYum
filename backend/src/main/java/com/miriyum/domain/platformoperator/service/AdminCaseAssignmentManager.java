package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentCommand;
import com.miriyum.domain.platformoperator.dto.authorization.AdminCaseAssignmentRequest;
import java.time.Instant;

public interface AdminCaseAssignmentManager {
    void assign(AdminCaseAssignmentCommand command);

    void reassign(AdminCaseAssignmentRequest currentAssignment, long nextOperatorId, Instant expiresAt);

    void close(AdminCaseAssignmentRequest currentAssignment);
}
