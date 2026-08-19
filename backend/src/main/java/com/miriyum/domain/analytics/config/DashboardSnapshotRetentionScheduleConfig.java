package com.miriyum.domain.analytics.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "miriyum.analytics.snapshot-retention.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DashboardSnapshotRetentionScheduleConfig {
}
