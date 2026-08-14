package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminCaseAssignmentRepository extends JpaRepository<AdminCaseAssignment, Long> {
    Optional<AdminCaseAssignment> findByCaseTypeAndCaseIdAndCaseVersionAndPlatformOperatorAccountId(
            AdminCaseType caseType,
            String caseId,
            long caseVersion,
            Long platformOperatorAccountId);
}
