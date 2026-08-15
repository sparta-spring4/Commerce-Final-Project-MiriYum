package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionTimedOutException;

@Component
@ConditionalOnProperty(
        name = "miriyum.waiting.compensation.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class WaitingConversionCompensationRunner {
    private static final int MAX_ITEMS_PER_POLL = 100;

    private final WaitingConversionCompensationService service;
    private final String owner;
    private final Duration leaseDuration;

    @Autowired
    public WaitingConversionCompensationRunner(
            WaitingConversionCompensationService service
    ) {
        this(service, "waiting-compensation-" + UUID.randomUUID(), Duration.ofSeconds(30));
    }

    WaitingConversionCompensationRunner(
            WaitingConversionCompensationService service,
            String owner,
            Duration leaseDuration
    ) {
        this.service = service;
        this.owner = owner;
        this.leaseDuration = leaseDuration;
    }

    @Scheduled(
            scheduler = "waitingConversionCompensationTaskScheduler",
            fixedDelayString = "${miriyum.waiting.compensation.fixed-delay-ms:5000}",
            initialDelayString = "${miriyum.waiting.compensation.initial-delay-ms:5000}")
    public void processBatch() {
        long afterId = 0L;
        for (int processed = 0; processed < MAX_ITEMS_PER_POLL; processed++) {
            var claimed = service.claimPending(owner, 1, leaseDuration, afterId);
            if (claimed.isEmpty()) {
                return;
            }
            WaitingCompensationClaim claim = claimed.getFirst();
            afterId = claim.compensationId();
            processSafely(claim);
        }
    }

    void processSafely(WaitingCompensationClaim claim) {
        try {
            service.processClaim(claim);
        } catch (RuntimeException failure) {
            boolean retryable = isRetryable(failure);
            boolean recorded = false;
            try {
                recorded = service.recordFailure(claim, retryable);
            } catch (RuntimeException recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            log.warn(
                    "event=waiting_conversion_compensation_failed "
                            + "compensation_id={} retryable={} recorded={} error_code={}",
                    claim.compensationId(), retryable, recorded, errorCode(failure), failure);
        }
    }

    @Scheduled(
            scheduler = "waitingConversionCompensationTaskScheduler",
            fixedDelayString =
                    "${miriyum.waiting.compensation.reconciliation-delay-ms:60000}",
            initialDelayString =
                    "${miriyum.waiting.compensation.reconciliation-delay-ms:60000}")
    public long reportReconciliationBacklog() {
        long pendingCount = service.countReconciliationRequired();
        if (pendingCount > 0) {
            log.warn(
                    "event=waiting_conversion_compensation_reconciliation_required "
                            + "pending_count={}",
                    pendingCount);
        }
        return pendingCount;
    }

    private static boolean isRetryable(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure;
                current != null && visited.add(current);
                current = current.getCause()) {
            if (current instanceof ServiceException serviceException) {
                return serviceException.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE
                        || serviceException.getErrorCode()
                        == CommonErrorCode.CONCURRENT_MODIFICATION;
            }
            if (current instanceof IllegalArgumentException
                    || current instanceof DataIntegrityViolationException) {
                return false;
            }
            if (current instanceof QueryTimeoutException
                    || current instanceof TransactionTimedOutException) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 1213
                    || sqlException.getErrorCode() == 1205)) {
                return true;
            }
        }
        return false;
    }

    private static String errorCode(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure;
                current != null && visited.add(current);
                current = current.getCause()) {
            if (current instanceof ServiceException serviceException) {
                return serviceException.getErrorCode().getCode();
            }
        }
        return failure.getClass().getSimpleName();
    }
}
