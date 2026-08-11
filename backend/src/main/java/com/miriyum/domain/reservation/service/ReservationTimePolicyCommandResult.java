package com.miriyum.domain.reservation.service;

public record ReservationTimePolicyCommandResult<T>(
        int httpStatus,
        T data
) {
}
