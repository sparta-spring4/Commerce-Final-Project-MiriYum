package com.miriyum.domain.store.onboarding.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoreOnboardingAutomaticCheckJob {

    private final StoreOnboardingAutomaticCheckTransaction transactions;
    private final BusinessRegistrationVerificationPort verifier;
    private final StoreOnboardingFinalizationService finalizer;

    @Scheduled(
            fixedDelayString = "${miriyum.store.onboarding.automatic-check-delay-ms:5000}",
            initialDelayString = "${miriyum.store.onboarding.automatic-check-initial-delay-ms:30000}")
    public void scheduledRun() {
        runOnce("store-onboarding-automatic-check");
    }

    public boolean runOnce(String owner) {
        return transactions.claim(owner).map(claim -> {
            var result = verifier.verify(transactions.request(claim));
            var outcome = transactions.record(claim, result);
            if (outcome == StoreOnboardingAutomaticCheckTransaction.RecordOutcome.AUTO_FINALIZE) {
                finalizer.finalizeApproved(
                        claim.applicationId(), claim.applicationVersion(),
                        StoreOnboardingFinalizationService.ApprovalMode.AUTO);
            }
            return true;
        }).orElse(false);
    }
}
