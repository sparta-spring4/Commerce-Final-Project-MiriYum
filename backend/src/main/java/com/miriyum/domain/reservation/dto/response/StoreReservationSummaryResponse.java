package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.Reservation;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 매장 운영자 예약 목록의 공개 항목이다.
 */
public record StoreReservationSummaryResponse(
        String reservationId,
        LocalDate serviceDate,
        CustomerReservationTimeStatus timeStatus,
        OffsetDateTime startAt,
        OffsetDateTime serviceEndAt,
        String timeZoneId,
        int totalPartySize,
        String status
) {

    public static StoreReservationSummaryResponse from(Reservation reservation) {
        CustomerReservationTimeResponse time =
                CustomerReservationTimeResponse.from(reservation.getTimeSnapshot());
        return new StoreReservationSummaryResponse(
                String.valueOf(reservation.getId()),
                time.serviceDate(),
                time.timeStatus(),
                time.startAt(),
                time.serviceEndAt(),
                time.timeZoneId(),
                reservation.getParty().totalCount(),
                reservation.getStatus().name()
        );
    }
}
