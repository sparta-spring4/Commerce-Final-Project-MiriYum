package com.miriyum.domain.store.onboarding.repository;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingAutomaticCheckJob;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreOnboardingAutomaticCheckJobRepository
        extends JpaRepository<StoreOnboardingAutomaticCheckJob, Long> {

    List<StoreOnboardingAutomaticCheckJob> findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Instant now);
}
