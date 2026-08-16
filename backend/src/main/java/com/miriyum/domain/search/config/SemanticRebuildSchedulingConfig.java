package com.miriyum.domain.search.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 운영자가 전체 백필을 활성화한 환경에서만 재색인 스케줄을 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "miriyum.store-search.semantic.rebuild-enabled",
        havingValue = "true")
public class SemanticRebuildSchedulingConfig {
}
