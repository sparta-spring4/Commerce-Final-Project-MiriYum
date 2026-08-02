package com.miriyum.domain.store.search.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 공개 매장 검색에서 예약 가용성을 요청하는 완전한 조건이다.
 */
public record ReservationSearchCondition(
        LocalDate serviceDate,
        LocalTime startTime,
        int partySize
) {
}
