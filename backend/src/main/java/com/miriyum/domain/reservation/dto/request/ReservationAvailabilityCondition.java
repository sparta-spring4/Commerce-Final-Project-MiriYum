package com.miriyum.domain.reservation.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 매장 하나 또는 여러 매장의 예약 가능 여부를 동일하게 판정할 조건이다.
 *
 * @param serviceDate 매장 업무 날짜
 * @param startTime 점유 시작 시각
 * @param endTime 점유 종료 시각
 * @param partySize 전체 일행 인원
 * @param includesInfants 영유아 동반 여부
 */
public record ReservationAvailabilityCondition(
        LocalDate serviceDate,
        LocalTime startTime,
        LocalTime endTime,
        int partySize,
        boolean includesInfants
) {

    private static final int MAX_PARTY_SIZE = 100;

    public ReservationAvailabilityCondition {
        if (serviceDate == null) {
            throw new IllegalArgumentException("serviceDate must not be null");
        }
        if (startTime == null) {
            throw new IllegalArgumentException("startTime must not be null");
        }
        if (endTime == null) {
            throw new IllegalArgumentException("endTime must not be null");
        }
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
        if (partySize < 1 || partySize > MAX_PARTY_SIZE) {
            throw new IllegalArgumentException("partySize must be between 1 and 100");
        }
    }
}
