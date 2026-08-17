package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.response.ReservationCheckInQrGrantResponse;

/** QR grant 발급 HTTP 상태와 credential-mint 응답이다. */
public record ReservationCheckInQrGrantResult(
        int httpStatus,
        ReservationCheckInQrGrantResponse data
) {
}
