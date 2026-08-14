package com.miriyum.domain.platformoperator.repository.membersupport;

import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberSupportCaseRepository extends JpaRepository<MemberSupportCase, Long> {
    Optional<MemberSupportCase> findByPublicId(String publicId);

    Optional<MemberSupportCase> findByIdentityVerificationIdAndStatus(
            Long identityVerificationId, MemberSupportCaseStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select supportCase from MemberSupportCase supportCase where supportCase.publicId = :publicId")
    Optional<MemberSupportCase> findByPublicIdForUpdate(@Param("publicId") String publicId);
}
