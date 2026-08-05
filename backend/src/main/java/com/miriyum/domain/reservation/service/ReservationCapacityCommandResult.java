package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.response.ReservationCapacitiesResponse;

public record ReservationCapacityCommandResult(
        int httpStatus,
        ReservationCapacitiesResponse data
) {
}
