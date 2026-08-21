package com.miriyum.domain.store.onboarding.repository;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingReviewCase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreOnboardingReviewCaseRepository
        extends JpaRepository<StoreOnboardingReviewCase, Long> {

    Optional<StoreOnboardingReviewCase> findByCasePublicId(String casePublicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reviewCase from StoreOnboardingReviewCase reviewCase "
            + "where reviewCase.casePublicId = :casePublicId")
    Optional<StoreOnboardingReviewCase> findByCasePublicIdForUpdate(
            @Param("casePublicId") String casePublicId);
}
