package com.miriyum.domain.store.onboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
@ConfigurationProperties(prefix = "miriyum.store.onboarding")
public class StoreOnboardingProperties {
    private boolean manualReviewEnabled;
    private long automaticCheckDelayMs;
    private long automaticCheckInitialDelayMs;
    private int automaticCheckBatchSize;
    private long automaticCheckLeaseSeconds;
    private long caseAssignmentDays;

    public StoreOnboardingProperties() {
    }

    public StoreOnboardingProperties(
            boolean manualReviewEnabled, long automaticCheckDelayMs,
            long automaticCheckInitialDelayMs, int automaticCheckBatchSize,
            long automaticCheckLeaseSeconds, long caseAssignmentDays) {
        this.manualReviewEnabled = manualReviewEnabled;
        this.automaticCheckDelayMs = automaticCheckDelayMs;
        this.automaticCheckInitialDelayMs = automaticCheckInitialDelayMs;
        this.automaticCheckBatchSize = automaticCheckBatchSize;
        this.automaticCheckLeaseSeconds = automaticCheckLeaseSeconds;
        this.caseAssignmentDays = caseAssignmentDays;
        validate();
    }

    @PostConstruct
    void validate() {
        if (automaticCheckDelayMs < 0 || automaticCheckInitialDelayMs < 0
                || automaticCheckBatchSize <= 0 || automaticCheckLeaseSeconds <= 0
                || caseAssignmentDays <= 0) {
            throw new IllegalArgumentException("store onboarding runtime values are invalid");
        }
    }

    public boolean manualReviewEnabled() { return manualReviewEnabled; }
    public long automaticCheckDelayMs() { return automaticCheckDelayMs; }
    public long automaticCheckInitialDelayMs() { return automaticCheckInitialDelayMs; }
    public int automaticCheckBatchSize() { return automaticCheckBatchSize; }
    public long automaticCheckLeaseSeconds() { return automaticCheckLeaseSeconds; }
    public long caseAssignmentDays() { return caseAssignmentDays; }

    public void setManualReviewEnabled(boolean value) { manualReviewEnabled = value; }
    public void setAutomaticCheckDelayMs(long value) { automaticCheckDelayMs = value; }
    public void setAutomaticCheckInitialDelayMs(long value) { automaticCheckInitialDelayMs = value; }
    public void setAutomaticCheckBatchSize(int value) { automaticCheckBatchSize = value; }
    public void setAutomaticCheckLeaseSeconds(long value) { automaticCheckLeaseSeconds = value; }
    public void setCaseAssignmentDays(long value) { caseAssignmentDays = value; }
}
