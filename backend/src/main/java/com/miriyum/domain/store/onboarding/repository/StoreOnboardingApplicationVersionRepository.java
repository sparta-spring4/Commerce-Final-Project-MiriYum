package com.miriyum.domain.store.onboarding.repository;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplicationVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreOnboardingApplicationVersionRepository
        extends JpaRepository<StoreOnboardingApplicationVersion, Long> {

    Optional<StoreOnboardingApplicationVersion> findByStoreOnboardingApplicationIdAndApplicationVersion(
            long storeOnboardingApplicationId, long applicationVersion);
}
