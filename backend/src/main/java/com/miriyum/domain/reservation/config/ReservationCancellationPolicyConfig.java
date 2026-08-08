package com.miriyum.domain.reservation.config;

import com.miriyum.domain.reservation.service.ReservationCancellationPolicyEvaluator;
import com.miriyum.domain.reservation.service.ReservationCancellationPolicyRegistry;
import com.miriyum.domain.reservation.service.ReservationCancellationPolicySelector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the fail-fast cancellation-policy version selection boundary for reservations.
 */
@Configuration
public class ReservationCancellationPolicyConfig {

    @Bean
    public ReservationCancellationPolicyRegistry reservationCancellationPolicyRegistry() {
        return new ReservationCancellationPolicyRegistry();
    }

    @Bean
    public ReservationCancellationPolicySelector reservationCancellationPolicySelector(
            ReservationCancellationPolicyRegistry reservationCancellationPolicyRegistry
    ) {
        return new ReservationCancellationPolicySelector(reservationCancellationPolicyRegistry);
    }

    @Bean
    public ReservationCancellationPolicyEvaluator reservationCancellationPolicyEvaluator(
            ReservationCancellationPolicyRegistry registry
    ) {
        return new ReservationCancellationPolicyEvaluator(registry);
    }
}
