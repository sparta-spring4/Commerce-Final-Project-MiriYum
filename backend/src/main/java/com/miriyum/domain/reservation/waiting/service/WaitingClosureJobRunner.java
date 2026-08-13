package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.global.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@ConditionalOnProperty(
        name = "miriyum.waiting.closure.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class WaitingClosureJobRunner {
    private final WaitingClosureService closureService;
    private final String ownerId;
    private final Duration leaseDuration;

    @Autowired
    public WaitingClosureJobRunner(WaitingClosureService closureService) {
        this(closureService, "waiting-closure-" + UUID.randomUUID(), Duration.ofSeconds(30));
    }

    WaitingClosureJobRunner(WaitingClosureService closureService, String ownerId, Duration leaseDuration) {
        this.closureService = closureService; this.ownerId = ownerId; this.leaseDuration = leaseDuration;
    }

    @Scheduled(
            fixedDelayString = "${miriyum.waiting.closure.fixed-delay-ms:5000}",
            initialDelayString = "${miriyum.waiting.closure.initial-delay-ms:5000}")
    public void processClosureBatch() {
        closureService.claimPendingItems(ownerId, 100, leaseDuration).forEach(this::processSafely);
    }

    void processSafely(WaitingClosureClaim claim) {
        try {
            closureService.processClaimedItem(claim);
        } catch (RuntimeException failure) {
            boolean retryable = WaitingClosureFailureClassifier.isRetryable(failure);
            try {
                closureService.recordFailure(claim, retryable);
            } catch (RuntimeException recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            if (!(failure instanceof ServiceException)) {
                log.warn("Waiting closure item processing failed. itemId={}", claim.itemId(), failure);
            }
        }
    }
}
