package com.miriyum.domain.store.evidence.repository;

import com.miriyum.domain.store.evidence.entity.BusinessRegistrationEvidence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 입점 신청 version별 현재 사업자등록증 증빙을 직렬화해 조회한다. */
public interface BusinessRegistrationEvidenceRepository extends JpaRepository<BusinessRegistrationEvidence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select evidence
            from BusinessRegistrationEvidence evidence
            where evidence.onboardingApplicationId = :applicationId
              and evidence.applicationVersion = :applicationVersion
              and evidence.currentMarker = 1
            """)
    Optional<BusinessRegistrationEvidence> findCurrentForUpdate(
            @Param("applicationId") long applicationId,
            @Param("applicationVersion") long applicationVersion);

    Optional<BusinessRegistrationEvidence> findByOnboardingApplicationIdAndApplicationVersionAndCurrentMarker(
            long onboardingApplicationId,
            long applicationVersion,
            Integer currentMarker);

    boolean existsByFileIdAndCurrentMarker(String fileId, Integer currentMarker);
}
