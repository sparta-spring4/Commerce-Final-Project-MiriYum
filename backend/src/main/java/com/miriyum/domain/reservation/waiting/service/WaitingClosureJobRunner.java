package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaitingClosureJobRunner {
    private final WaitingClosureService closureService;

    @Scheduled(
            fixedDelayString = "${miriyum.waiting.closure.fixed-delay-ms:5000}",
            initialDelayString = "${miriyum.waiting.closure.initial-delay-ms:5000}")
    public void processClosureBatch() {
        closureService.claimPendingItems(100).forEach(this::processSafely);
    }

    void processSafely(long itemId) {
        try {
            closureService.processClaimedItem(itemId);
        } catch (RuntimeException failure) {
            boolean retryable = failure instanceof TransientDataAccessException;
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
