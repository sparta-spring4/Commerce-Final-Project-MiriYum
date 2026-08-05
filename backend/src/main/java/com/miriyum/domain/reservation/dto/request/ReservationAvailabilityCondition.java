package com.miriyum.domain.reservation.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * 매장 하나 또는 여러 매장의 예약 가능 여부를 동일하게 판정할 조건이다.
 *
 * @param serviceDate 매장 업무 날짜
 * @param startTime 점유 시작 시각
 * @param startOffset DST 중복 현지 시각을 식별할 offset 또는 {@code null}
 * @param partySize 전체 일행 인원
 * @param includesInfants 영유아 동반 여부
 */
public record ReservationAvailabilityCondition(
        LocalDate serviceDate,
        LocalTime startTime,
        ZoneOffset startOffset,
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
        if (startTime.getSecond() != 0 || startTime.getNano() != 0) {
            throw new IllegalArgumentException("startTime must use minute precision");
        }
        if (partySize < 1 || partySize > MAX_PARTY_SIZE) {
            throw new IllegalArgumentException("partySize must be between 1 and 100");
        }
    }
}
