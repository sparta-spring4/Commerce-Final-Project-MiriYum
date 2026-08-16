package com.miriyum.domain.reservation.waiting.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Waiting 예약 전환 보상 polling을 공용 scheduler와 분리한다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "miriyum.waiting.compensation.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WaitingConversionCompensationSchedulingConfig {

    @Bean(
            name = "waitingConversionCompensationTaskScheduler",
            destroyMethod = "shutdown",
            defaultCandidate = false)
    ThreadPoolTaskScheduler waitingConversionCompensationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("waiting-conversion-compensation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
