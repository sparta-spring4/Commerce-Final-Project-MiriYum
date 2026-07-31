package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.Reservation;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 매장 운영자 예약 목록의 공개 항목이다.
 */
public record StoreReservationSummaryResponse(
        String reservationId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalTime endTime,
        int totalPartySize,
        String status
) {

    public static StoreReservationSummaryResponse from(Reservation reservation) {
        return new StoreReservationSummaryResponse(
                String.valueOf(reservation.getId()),
                reservation.getServiceDate(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getParty().totalCount(),
                reservation.getStatus().name()
        );
    }
}
