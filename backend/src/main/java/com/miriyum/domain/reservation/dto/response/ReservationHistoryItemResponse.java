package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.Reservation;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 소비자 예약 내역의 공개 항목이다.
 *
 * <p>내부 숫자 식별자는 공개 문자열로 변환하고, 매장명은 예약 시점 스냅샷을 사용한다.
 */
public record ReservationHistoryItemResponse(
        String reservationId,
        String storeId,
        String storeName,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalTime endTime,
        int partySize,
        String status,
        OffsetDateTime createdAt
) {

    public static ReservationHistoryItemResponse from(Reservation reservation) {
        return new ReservationHistoryItemResponse(
                String.valueOf(reservation.getId()),
                String.valueOf(reservation.getStoreId()),
                reservation.getStoreNameSnapshot(),
                reservation.getServiceDate(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getParty().totalCount(),
                reservation.getStatus().name(),
                reservation.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
