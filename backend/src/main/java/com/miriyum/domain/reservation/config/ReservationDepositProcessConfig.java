package com.miriyum.domain.reservation.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reservation-owned operational timing for deposit process workers. */
@Configuration
public class ReservationDepositProcessConfig {

    @Bean("reservationDepositRefundLeaseDuration")
    public Duration reservationDepositRefundLeaseDuration() {
        return Duration.ofSeconds(30);
    }
}
