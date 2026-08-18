package com.miriyum.global.storage.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** S3 정리 실패가 공용 scheduler를 막지 않도록 전용 단일 worker를 사용한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileStorageReconciliationProperties.class)
@Import(FileStorageReconciliationSchedulingConfig.SchedulingActivation.class)
public class FileStorageReconciliationSchedulingConfig {

    @Bean(name = "fileStorageReconciliationTaskScheduler", defaultCandidate = false)
    @ConditionalOnProperty(
            prefix = "miriyum.storage.s3",
            name = {"enabled", "reconciliation.enabled"},
            havingValue = "true")
    ThreadPoolTaskScheduler fileStorageReconciliationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("file-storage-reconciliation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            prefix = "miriyum.storage.s3",
            name = {"enabled", "reconciliation.enabled"},
            havingValue = "true")
    static class SchedulingActivation {
    }
}
