package com.miriyum.domain.auth.riskevent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Refresh Token 위험 사건 전달 재시도를 활성화한다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "miriyum.auth.refresh-risk-event-delivery.enabled",
        havingValue = "true"
)
public class RefreshTokenRiskEventSchedulingConfig {
}
