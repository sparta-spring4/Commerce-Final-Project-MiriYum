package com.miriyum.domain.reservation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** ReservationHold 만료와 대사 장기 체류 관측의 전용 scheduling 경계다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "miriyum.reservation.hold-expiration.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationHoldExpirationConfig {

    @Bean("reservationHoldExpirationBatchSize")
    Integer reservationHoldExpirationBatchSize(
            @Value("${miriyum.reservation.hold-expiration.batch-size:100}") int value
    ) {
        return requirePositive(value, "batch-size");
    }

    @Bean("reservationHoldExpirationPollDelayMs")
    Long reservationHoldExpirationPollDelayMs(
            @Value("${miriyum.reservation.hold-expiration.poll-delay-ms:1000}") long value
    ) {
        return requirePositive(value, "poll-delay-ms");
    }

    @Bean("reservationHoldReconciliationPollDelayMs")
    Long reservationHoldReconciliationPollDelayMs(
            @Value(
                    "${miriyum.reservation.hold-expiration"
                            + ".reconciliation-poll-delay-ms:60000}") long value
    ) {
        return requirePositive(value, "reconciliation-poll-delay-ms");
    }

    @Bean(
            name = "reservationHoldExpirationScheduler",
            destroyMethod = "shutdown",
            defaultCandidate = false
    )
    ThreadPoolTaskScheduler reservationHoldExpirationScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("reservation-hold-expiration-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    private static int requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }
}
