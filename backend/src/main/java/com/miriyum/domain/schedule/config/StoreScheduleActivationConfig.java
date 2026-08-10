package com.miriyum.domain.schedule.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "miriyum.store.schedule.activation-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StoreScheduleActivationConfig {
}
