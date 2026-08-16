package com.miriyum.domain.platformoperator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class PlatformOperatorStoreSanctionSchedulingConfig {
}
