package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.config.WaitingAutoOpenProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
public class WaitingAutoOpenJobRunner {

    private final WaitingAutoOpenPlanner planner;
    private final WaitingAutoOpenService service;
    private final WaitingAutoOpenMetrics metrics;
    private final WaitingAutoOpenProperties properties;
    private final Clock clock;

    public WaitingAutoOpenJobRunner(
            WaitingAutoOpenPlanner planner,
            WaitingAutoOpenService service,
            WaitingAutoOpenMetrics metrics,
            WaitingAutoOpenProperties properties,
            Clock clock
    ) {
        this.planner = Objects.requireNonNull(planner);
        this.service = Objects.requireNonNull(service);
        this.metrics = Objects.requireNonNull(metrics);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(
            scheduler = "waitingAutoOpenTaskScheduler",
            fixedDelayString = "${miriyum.waiting.auto-open.poll-delay}",
            initialDelayString = "${miriyum.waiting.auto-open.initial-delay}")
    public void poll() {
        Instant now = clock.instant();
        service.invalidateStale(now, properties.invalidationBatchSize());
        planner.plan(now, properties.planningHorizon(), properties.planningBatchSize());
        for (WaitingAutoOpenClaim claim : service.claimDue(
                properties.workerId(),
                now,
                properties.leaseDuration(),
                properties.claimBatchSize())) {
            executeSafely(claim, now);
        }
    }

    private void executeSafely(WaitingAutoOpenClaim claim, Instant now) {
        try {
            WaitingAutoOpenService.ExecutionResult result = service.execute(claim, now);
            metrics.execution(result);
            log.info(
                    "event=waiting_auto_open_execution outcome={} job_id={} store_id={} "
                            + "interval_key={} settings_version={} owner={} fence={} attempt={}",
                    result, claim.jobId(), claim.storeId(), claim.businessIntervalKey(),
                    claim.expectedSettingsVersion(), claim.leaseOwner(),
                    claim.fencingToken(), claim.attemptCount());
        } catch (RuntimeException failure) {
            boolean recorded = false;
            try {
                recorded = service.recordFailure(
                        claim,
                        now,
                        failure,
                        properties.maxAttempts(),
                        properties.initialRetryDelay(),
                        properties.maximumRetryDelay());
            } catch (RuntimeException recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            metrics.failure(failure);
            log.warn(
                    "event=waiting_auto_open_failure recorded={} job_id={} store_id={} "
                            + "interval_key={} settings_version={} owner={} fence={} attempt={} "
                            + "failure_class={}",
                    recorded, claim.jobId(), claim.storeId(), claim.businessIntervalKey(),
                    claim.expectedSettingsVersion(), claim.leaseOwner(),
                    claim.fencingToken(), claim.attemptCount(),
                    failure.getClass().getSimpleName(), failure);
        }
    }
}
