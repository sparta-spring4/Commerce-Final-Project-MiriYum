package com.miriyum.domain.auth.riskevent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Refresh Token 위험 사건 전달을 공용 scheduler와 분리해 활성화한다. */
@Configuration
@Import(RefreshTokenRiskEventSchedulingConfig.WorkerSchedulingActivation.class)
public class RefreshTokenRiskEventSchedulingConfig {

    @Bean(
            name = "refreshTokenRiskEventTaskScheduler",
            defaultCandidate = false
    )
    @ConditionalOnProperty(
            name = "miriyum.auth.refresh-risk-event-delivery.enabled",
            havingValue = "true"
    )
    ThreadPoolTaskScheduler refreshTokenRiskEventTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("refresh-risk-event-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(
            name = "miriyum.auth.refresh-risk-event-delivery.enabled",
            havingValue = "true"
    )
    static class WorkerSchedulingActivation {
    }
}
