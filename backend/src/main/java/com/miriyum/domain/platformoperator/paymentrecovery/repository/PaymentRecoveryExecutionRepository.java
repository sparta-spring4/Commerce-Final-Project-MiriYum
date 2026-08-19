package com.miriyum.domain.platformoperator.paymentrecovery.repository;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryExecution;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ExecutionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRecoveryExecutionRepository
        extends JpaRepository<PaymentRecoveryExecution, Long> {
    Optional<PaymentRecoveryExecution> findByExecutionKey(String executionKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from PaymentRecoveryExecution execution where execution.id = :id")
    Optional<PaymentRecoveryExecution> findByIdForUpdate(@Param("id") long id);

    Optional<PaymentRecoveryExecution> findByCasePublicIdAndProposalVersion(
            String casePublicId, long proposalVersion);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution from PaymentRecoveryExecution execution
            where execution.status in :statuses
              and execution.nextAttemptAt <= :now
              and (execution.leaseExpiresAt is null or execution.leaseExpiresAt <= :now)
            order by execution.id
            """)
    List<PaymentRecoveryExecution> findDueForUpdate(
            @Param("statuses") Collection<ExecutionStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable);
}
