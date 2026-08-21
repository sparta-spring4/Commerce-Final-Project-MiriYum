package com.miriyum.domain.store.onboarding.repository;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingDecision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreOnboardingDecisionRepository
        extends JpaRepository<StoreOnboardingDecision, Long> {

    Optional<StoreOnboardingDecision> findByCasePublicIdAndCaseVersion(
            String casePublicId, long caseVersion);
}
