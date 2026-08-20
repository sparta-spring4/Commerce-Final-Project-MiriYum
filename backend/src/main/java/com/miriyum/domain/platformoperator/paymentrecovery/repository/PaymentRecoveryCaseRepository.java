package com.miriyum.domain.platformoperator.paymentrecovery.repository;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.CaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRecoveryCaseRepository extends JpaRepository<PaymentRecoveryCase, Long> {
    Optional<PaymentRecoveryCase> findByHandoffId(String handoffId);

    Optional<PaymentRecoveryCase> findByPublicId(String publicId);

    Page<PaymentRecoveryCase> findByStatus(CaseStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recovery from PaymentRecoveryCase recovery where recovery.publicId = :publicId")
    Optional<PaymentRecoveryCase> findByPublicIdForUpdate(@Param("publicId") String publicId);
}
