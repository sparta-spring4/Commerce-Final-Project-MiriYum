package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionWindow;
import com.miriyum.domain.reservation.waiting.repository.WaitingAutoOpenJobRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingReceptionWindowRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WaitingAutoOpenService {

    private final WaitingAutoOpenJobRepository jobRepository;
    private final WaitingReceptionWindowRepository windowRepository;
    private final WaitingSettingRepository settingRepository;
    private final WaitingOperatingIntervalPort intervalPort;
    private final WaitingAutoOpenFailureClassifier failureClassifier;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public List<WaitingAutoOpenClaim> claimDue(
            String owner,
            Instant now,
            Duration leaseDuration,
            int batchSize
    ) {
        if (owner == null || owner.isBlank() || now == null
                || leaseDuration == null || leaseDuration.isNegative()
                || leaseDuration.isZero() || batchSize <= 0) {
            throw new IllegalArgumentException("claim policy is invalid");
        }
        List<Long> ids = jobRepository.findClaimableIds(now, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        Instant leaseUntil = now.plus(leaseDuration);
        List<WaitingAutoOpenJob> jobs = jobRepository.findAllForUpdateByIdIn(ids);
        jobs.forEach(job -> job.claim(owner, now, leaseUntil));
        jobRepository.flush();
        return jobs.stream().map(WaitingAutoOpenService::claimSnapshot).toList();
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public boolean recordFailure(
            WaitingAutoOpenClaim claim,
            Instant now,
            RuntimeException failure,
            int maxAttempts,
            Duration initialRetryDelay,
            Duration maximumRetryDelay
    ) {
        if (claim == null || now == null || failure == null || maxAttempts <= 0
                || initialRetryDelay == null || initialRetryDelay.isNegative()
                || initialRetryDelay.isZero()
                || maximumRetryDelay == null
                || maximumRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException("failure policy is invalid");
        }
        WaitingAutoOpenJob job = jobRepository.findByIdForUpdate(claim.jobId()).orElse(null);
        if (job == null
                || !job.isOwnedBy(claim.leaseOwner(), claim.fencingToken(), now)) {
            return false;
        }
        WaitingAutoOpenFailureClassifier.Decision decision =
                failureClassifier.classify(failure);
        if (decision.retryable() && job.getAttemptCount() < maxAttempts) {
            job.retry(
                    claim.leaseOwner(),
                    claim.fencingToken(),
                    now,
                    backoff(initialRetryDelay, maximumRetryDelay, job.getAttemptCount()),
                    decision.code());
        } else {
            job.requireReconciliation(
                    claim.leaseOwner(),
                    claim.fencingToken(),
                    now,
                    decision.code());
        }
        jobRepository.flush();
        return true;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public int invalidateStale(Instant now, int batchSize) {
        if (now == null || batchSize <= 0) {
            throw new IllegalArgumentException("invalidation batch is invalid");
        }
        return jobRepository.invalidateStaleUnclaimed(now, batchSize);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public ExecutionResult execute(WaitingAutoOpenClaim claim, Instant now) {
        if (claim == null || now == null) {
            throw new IllegalArgumentException("execution claim is invalid");
        }
        WaitingOperatingInterval interval = intervalPort.lockCurrent(
                        claim.storeId(),
                        claim.businessIntervalKey(),
                        claim.intervalStartsAt(),
                        claim.intervalEndsAt())
                .orElse(null);
        WaitingAutoOpenJob job = jobRepository.findByIdForUpdate(claim.jobId()).orElse(null);
        if (job == null
                || !job.isOwnedBy(claim.leaseOwner(), claim.fencingToken(), now)) {
            return ExecutionResult.STALE_CLAIM;
        }
        if (interval == null
                || !interval.businessDate().equals(claim.businessDate())
                || now.isBefore(job.getScheduledAt())
                || !now.isBefore(job.getIntervalEndsAt())) {
            job.invalidate(
                    claim.leaseOwner(),
                    claim.fencingToken(),
                    now,
                    "STALE_INTERVAL");
            jobRepository.flush();
            return ExecutionResult.INVALIDATED;
        }
        int fenced = settingRepository.compareAndFenceAutoOpen(
                claim.storeId(),
                claim.expectedSettingsVersion(),
                claim.expectedAdvanceOpenMinutes(),
                now);
        if (fenced != 1) {
            job.invalidate(
                    claim.leaseOwner(),
                    claim.fencingToken(),
                    now,
                    "STALE_SETTINGS");
            jobRepository.flush();
            return ExecutionResult.INVALIDATED;
        }
        windowRepository.saveAndFlush(WaitingReceptionWindow.opened(
                job.getId(),
                claim.storeId(),
                claim.businessIntervalKey(),
                claim.businessDate(),
                job.getScheduledAt(),
                job.getIntervalEndsAt(),
                claim.expectedSettingsVersion(),
                now));
        job.complete(claim.leaseOwner(), claim.fencingToken(), now);
        jobRepository.flush();
        return ExecutionResult.COMPLETED;
    }

    private static Duration backoff(
            Duration initial,
            Duration maximum,
            int attemptCount
    ) {
        Duration delay = initial;
        for (int attempt = 1; attempt < attemptCount; attempt++) {
            if (delay.compareTo(maximum.dividedBy(2)) > 0) {
                return maximum;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximum) > 0 ? maximum : delay;
    }

    private static WaitingAutoOpenClaim claimSnapshot(WaitingAutoOpenJob job) {
        return new WaitingAutoOpenClaim(
                job.getId(),
                job.getStoreId(),
                job.getBusinessIntervalKey(),
                job.getBusinessDate(),
                job.getIntervalStartsAt(),
                job.getIntervalEndsAt(),
                job.getExpectedSettingsVersion(),
                job.getExpectedAdvanceOpenMinutes(),
                job.getLeaseOwner(),
                job.getFencingToken(),
                job.getAttemptCount(),
                job.getLeaseUntil());
    }

    public enum ExecutionResult {
        COMPLETED,
        INVALIDATED,
        STALE_CLAIM
    }
}
