package com.miriyum.domain.reservation.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * 매장별 예약 시간 계산에 공통으로 적용할 고객 선택 시작 시각이다.
 *
 * @param serviceDate 매장 현지 업무 날짜
 * @param startTime 매장 현지 시작 시각
 * @param startOffset DST 중복 시각을 식별할 명시적 offset 또는 {@code null}
 */
public record ReservationTimeRequest(
        LocalDate serviceDate,
        LocalTime startTime,
        ZoneOffset startOffset
) {

    public ReservationTimeRequest {
        if (serviceDate == null || startTime == null) {
            throw new IllegalArgumentException("serviceDate and startTime are required");
        }
        if (startTime.getSecond() != 0 || startTime.getNano() != 0) {
            throw new IllegalArgumentException("startTime must use minute precision");
        }
    }
}
