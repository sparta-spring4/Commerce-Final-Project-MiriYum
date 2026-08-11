package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;

public record ReservationFulfillmentCommandResult(
        int httpStatus,
        ReservationDetailResponse data
) {
}
