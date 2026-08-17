package com.miriyum.domain.reservation.waiting.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "miriyum.waiting.auto-open")
public record WaitingAutoOpenProperties(
        String workerId,
        Duration planningHorizon,
        int planningBatchSize,
        int claimBatchSize,
        Duration leaseDuration,
        int maxAttempts,
        Duration initialRetryDelay,
        Duration maximumRetryDelay,
        int invalidationBatchSize,
        Duration pollDelay,
        Duration initialDelay
) {
    public WaitingAutoOpenProperties {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128
                || !positive(planningHorizon)
                || planningBatchSize <= 0
                || claimBatchSize <= 0
                || !positive(leaseDuration)
                || maxAttempts <= 0
                || !positive(initialRetryDelay)
                || maximumRetryDelay == null
                || maximumRetryDelay.compareTo(initialRetryDelay) < 0
                || invalidationBatchSize <= 0
                || !positive(pollDelay)
                || initialDelay == null
                || initialDelay.isNegative()) {
            throw new IllegalArgumentException("enabled waiting auto-open configuration is invalid");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
