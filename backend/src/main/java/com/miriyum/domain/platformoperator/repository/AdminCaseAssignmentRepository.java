package com.miriyum.domain.platformoperator.repository;

import com.miriyum.domain.platformoperator.entity.AdminCaseAssignment;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminCaseAssignmentRepository extends JpaRepository<AdminCaseAssignment, Long> {
    @Query("""
            select assignment from AdminCaseAssignment assignment
             where assignment.caseType = :caseType
               and assignment.caseId = :caseId
               and assignment.caseVersion = :caseVersion
            """)
    Optional<AdminCaseAssignment> findByCase(
            @Param("caseType") AdminCaseType caseType,
            @Param("caseId") String caseId,
            @Param("caseVersion") long caseVersion);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select assignment from AdminCaseAssignment assignment
             where assignment.caseType = :caseType
               and assignment.caseId = :caseId
               and assignment.caseVersion = :caseVersion
            """)
    Optional<AdminCaseAssignment> findByCaseForUpdate(
            @Param("caseType") AdminCaseType caseType,
            @Param("caseId") String caseId,
            @Param("caseVersion") long caseVersion);
}
