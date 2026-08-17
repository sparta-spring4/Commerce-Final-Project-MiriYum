package com.miriyum.domain.reservation.dto.response;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** credential-mint 성공에서만 raw QR를 반환하는 예약별 grant 응답이다. */
public record ReservationCheckInQrGrantResponse(
        String reservationId,
        String qrToken,
        long tokenVersion,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt
) {

    public static ReservationCheckInQrGrantResponse of(
            long reservationId,
            String qrToken,
            long tokenVersion,
            Instant issuedAt,
            Instant expiresAt
    ) {
        if (reservationId <= 0
                || qrToken == null
                || tokenVersion <= 0
                || issuedAt == null
                || expiresAt == null) {
            throw new IllegalArgumentException("complete QR grant response data is required");
        }
        return new ReservationCheckInQrGrantResponse(
                String.valueOf(reservationId),
                qrToken,
                tokenVersion,
                issuedAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC)
        );
    }
}
