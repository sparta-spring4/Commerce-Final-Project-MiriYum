package com.miriyum.domain.platformoperator.repository.membersupport;

import com.miriyum.domain.platformoperator.entity.membersupport.MemberIdentityVerification;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberIdentityVerificationRepository extends JpaRepository<MemberIdentityVerification, Long> {
    Optional<MemberIdentityVerification> findByProofDigest(String digest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select verification from MemberIdentityVerification verification where verification.proofDigest = :digest")
    Optional<MemberIdentityVerification> findByProofDigestForUpdate(@Param("digest") String digest);
}
