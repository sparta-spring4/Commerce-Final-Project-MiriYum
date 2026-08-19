package com.miriyum.domain.payment.repository;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType;
import com.miriyum.domain.payment.entity.PaymentRecoveryHandoff;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRecoveryHandoffRepository
        extends JpaRepository<PaymentRecoveryHandoff, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select handoff from PaymentRecoveryHandoff handoff
            where handoff.sourceType = :sourceType and handoff.sourceId = :sourceId
            """)
    Optional<PaymentRecoveryHandoff> findBySourceTypeAndSourceIdForUpdate(
            @Param("sourceType") ManualRecoverySourceType sourceType,
            @Param("sourceId") String sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select handoff from PaymentRecoveryHandoff handoff
            where handoff.status = com.miriyum.domain.payment.entity.PaymentRecoveryHandoff.Status.AVAILABLE
               or (handoff.status = com.miriyum.domain.payment.entity.PaymentRecoveryHandoff.Status.CLAIMED
                   and handoff.leaseUntil <= :now)
            order by handoff.id asc
            """)
    List<PaymentRecoveryHandoff> findClaimableForUpdate(
            @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select handoff from PaymentRecoveryHandoff handoff where handoff.id = :id")
    Optional<PaymentRecoveryHandoff> findByIdForUpdate(@Param("id") long id);
}
