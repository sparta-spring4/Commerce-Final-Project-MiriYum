package com.miriyum.domain.store.onboarding.repository;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingReviewCase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseStatus;
import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.ReviewCaseType;

public interface StoreOnboardingReviewCaseRepository
        extends JpaRepository<StoreOnboardingReviewCase, Long> {

    Optional<StoreOnboardingReviewCase> findByCasePublicId(String casePublicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reviewCase from StoreOnboardingReviewCase reviewCase "
            + "where reviewCase.casePublicId = :casePublicId")
    Optional<StoreOnboardingReviewCase> findByCasePublicIdForUpdate(
            @Param("casePublicId") String casePublicId);

    Page<StoreOnboardingReviewCase> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select reviewCase from StoreOnboardingReviewCase reviewCase
            where (:status is null or reviewCase.status = :status)
              and (:type is null or reviewCase.caseType = :type)
            order by reviewCase.createdAt desc
            """)
    Page<StoreOnboardingReviewCase> search(
            @Param("status") ReviewCaseStatus status,
            @Param("type") ReviewCaseType type,
            Pageable pageable);
}
