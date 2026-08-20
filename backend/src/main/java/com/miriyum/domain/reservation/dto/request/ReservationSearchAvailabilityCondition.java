package com.miriyum.domain.reservation.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * 검색 후보 매장의 예약 가능 여부를 부분 조건으로 판정한다.
 *
 * @param serviceDate 매장 업무 날짜
 * @param startTime 선택한 시작 시각 또는 {@code null}
 * @param startOffset DST 중복 시각을 식별할 offset 또는 {@code null}
 * @param partySize 전체 일행 인원 또는 {@code null}
 * @param includesInfants 영유아 동반 여부
 */
public record ReservationSearchAvailabilityCondition(
        LocalDate serviceDate,
        LocalTime startTime,
        ZoneOffset startOffset,
        Integer partySize,
        boolean includesInfants
) {

    private static final int MAX_PARTY_SIZE = 100;

    public ReservationSearchAvailabilityCondition {
        if (serviceDate == null) {
            throw new IllegalArgumentException("serviceDate must not be null");
        }
        if (startTime == null && startOffset != null) {
            throw new IllegalArgumentException("startOffset requires startTime");
        }
        if (startTime != null
                && (startTime.getSecond() != 0 || startTime.getNano() != 0)) {
            throw new IllegalArgumentException("startTime must use minute precision");
        }
        if (partySize != null && (partySize < 1 || partySize > MAX_PARTY_SIZE)) {
            throw new IllegalArgumentException("partySize must be between 1 and 100");
        }
    }
}
