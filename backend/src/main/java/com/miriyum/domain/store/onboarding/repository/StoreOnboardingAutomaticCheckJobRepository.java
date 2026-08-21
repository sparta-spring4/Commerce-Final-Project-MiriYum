package com.miriyum.domain.store.onboarding.repository;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingAutomaticCheckJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreOnboardingAutomaticCheckJobRepository
        extends JpaRepository<StoreOnboardingAutomaticCheckJob, Long> {

    List<StoreOnboardingAutomaticCheckJob> findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from StoreOnboardingAutomaticCheckJob job
            where (job.status = com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.AutomaticCheckStatus.PENDING
                    and job.nextAttemptAt <= :now)
               or (job.status = com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.AutomaticCheckStatus.PROCESSING
                    and job.leaseExpiresAt <= :now)
            order by job.nextAttemptAt asc, job.id asc
            """)
    List<StoreOnboardingAutomaticCheckJob> findClaimableForUpdate(
            @Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from StoreOnboardingAutomaticCheckJob job where job.id = :id")
    Optional<StoreOnboardingAutomaticCheckJob> findByIdForUpdate(@Param("id") long id);

    Optional<StoreOnboardingAutomaticCheckJob> findByStoreOnboardingApplicationIdAndApplicationVersion(
            long applicationId, long applicationVersion);
}
