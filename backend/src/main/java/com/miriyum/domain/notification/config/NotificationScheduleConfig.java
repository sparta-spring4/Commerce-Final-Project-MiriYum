package com.miriyum.domain.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Notification worker의 설정 바인딩과 주기 실행을 활성화한다.
 */
@Configuration
@EnableConfigurationProperties(NotificationSettings.class)
@Import(NotificationScheduleConfig.WorkerSchedulingActivation.class)
public class NotificationScheduleConfig {

    @Bean("notificationWorkerPollDelayMs")
    Long notificationWorkerPollDelayMs(NotificationSettings settings) {
        return settings.schedulingDelayMillis();
    }

    @Bean("notificationWorkerInitialDelayMs")
    Long notificationWorkerInitialDelayMs(NotificationSettings settings) {
        return settings.schedulingInitialDelayMillis();
    }

    @Bean(
            name = "notificationTaskScheduler",
            destroyMethod = "shutdown",
            defaultCandidate = false
    )
    @ConditionalOnProperty(
            name = "miriyum.notification.worker.enabled",
            havingValue = "true"
    )
    ThreadPoolTaskScheduler notificationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(
            name = "miriyum.notification.worker.enabled",
            havingValue = "true"
    )
    static class WorkerSchedulingActivation {
    }
}
