package com.miriyum.domain.reservation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "miriyum.reservation.time-policy.activation-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationTimePolicyActivationConfig {
}
