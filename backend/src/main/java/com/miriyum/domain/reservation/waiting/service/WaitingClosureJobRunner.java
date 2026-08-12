package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaitingClosureJobRunner {
    private final WaitingClosureService closureService;
    private final AtomicBoolean recoveryComplete = new AtomicBoolean();

    @Scheduled(
            fixedDelayString = "${miriyum.waiting.closure.fixed-delay-ms:5000}",
            initialDelayString = "${miriyum.waiting.closure.initial-delay-ms:5000}")
    public void processClosureBatch() {
        if (recoveryComplete.compareAndSet(false, true)) {
            try {
                closureService.recoverStrandedWork();
            } catch (RuntimeException failure) {
                recoveryComplete.set(false);
                throw failure;
            }
        }
        closureService.claimPendingItems(100).forEach(this::processSafely);
    }

    void processSafely(long itemId) {
        try {
            closureService.processClaimedItem(itemId);
        } catch (RuntimeException failure) {
            boolean retryable = WaitingClosureFailureClassifier.isRetryable(failure);
            try {
                closureService.recordFailure(itemId, retryable);
            } catch (RuntimeException recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            if (!(failure instanceof ServiceException)) {
                log.warn("Waiting closure item processing failed. itemId={}", itemId, failure);
            }
        }
    }
}
