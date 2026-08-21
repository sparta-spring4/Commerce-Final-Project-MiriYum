package com.miriyum.domain.store.onboarding.repository;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplication;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreOnboardingApplicationRepository
        extends JpaRepository<StoreOnboardingApplication, Long> {

    Optional<StoreOnboardingApplication> findByStoreOperatorAccountIdAndSubmissionIdempotencyKey(
            long storeOperatorAccountId, String submissionIdempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from StoreOnboardingApplication application where application.id = :id")
    Optional<StoreOnboardingApplication> findByIdForUpdate(@Param("id") long id);
}
