package com.miriyum.domain.schedule.dto.contract;

import java.time.DayOfWeek;
import java.util.List;

/** 공개 조회에 필요한 요일별 예약 가능 시간이다. */
public record PublicReservationDay(
        DayOfWeek dayOfWeek,
        List<PublicScheduleTimeRange> timeSlots
) {
    public PublicReservationDay {
        timeSlots = List.copyOf(timeSlots);
    }
}
