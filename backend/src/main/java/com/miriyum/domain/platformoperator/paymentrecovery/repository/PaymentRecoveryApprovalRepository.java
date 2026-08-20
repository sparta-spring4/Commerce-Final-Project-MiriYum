package com.miriyum.domain.platformoperator.paymentrecovery.repository;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryApproval;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRecoveryApprovalRepository
        extends JpaRepository<PaymentRecoveryApproval, Long> {
    Optional<PaymentRecoveryApproval> findByCasePublicIdAndProposalVersion(
            String casePublicId, long proposalVersion);
}
