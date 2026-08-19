package com.miriyum.domain.platformoperator.paymentrecovery.repository;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryProposal;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRecoveryProposalRepository
        extends JpaRepository<PaymentRecoveryProposal, Long> {
    Optional<PaymentRecoveryProposal> findByCasePublicIdAndProposalVersion(
            String casePublicId, long proposalVersion);

    Optional<PaymentRecoveryProposal> findByCasePublicIdAndRequestFingerprint(
            String casePublicId, String requestFingerprint);

    List<PaymentRecoveryProposal> findByCasePublicIdOrderByProposalVersionAsc(String casePublicId);
}
