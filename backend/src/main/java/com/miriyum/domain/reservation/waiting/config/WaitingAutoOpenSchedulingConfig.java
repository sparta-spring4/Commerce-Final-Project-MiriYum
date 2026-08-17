package com.miriyum.domain.reservation.waiting.config;

import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenJobRunner;
import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenMetrics;
import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenPlanner;
import com.miriyum.domain.reservation.waiting.service.WaitingAutoOpenService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(WaitingAutoOpenProperties.class)
@ConditionalOnProperty(
        name = "miriyum.waiting.auto-open.enabled",
        havingValue = "true")
public class WaitingAutoOpenSchedulingConfig {

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler waitingAutoOpenTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("waiting-auto-open-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean
    WaitingAutoOpenMetrics waitingAutoOpenMetrics(MeterRegistry meterRegistry) {
        return new WaitingAutoOpenMetrics(meterRegistry);
    }

    @Bean
    WaitingAutoOpenJobRunner waitingAutoOpenJobRunner(
            WaitingAutoOpenPlanner planner,
            WaitingAutoOpenService service,
            WaitingAutoOpenMetrics metrics,
            WaitingAutoOpenProperties properties,
            Clock clock
    ) {
        return new WaitingAutoOpenJobRunner(planner, service, metrics, properties, clock);
    }
}
