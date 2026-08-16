package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 매장 운영자가 스캔한 strict v1 opaque QR credential 입력이다. */
public record ReservationCheckInRequest(
        @NotBlank
        @Pattern(regexp = "^rqg_v1_[A-Za-z0-9_-]{43}$")
        String qrToken
) {
}
