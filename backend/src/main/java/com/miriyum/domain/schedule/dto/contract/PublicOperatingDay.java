package com.miriyum.domain.schedule.dto.contract;

import java.time.DayOfWeek;
import java.util.List;

/** 공개 조회에 필요한 요일별 영업·휴게 시간이다. */
public record PublicOperatingDay(
        DayOfWeek dayOfWeek,
        List<PublicScheduleTimeRange> businessHours,
        List<PublicScheduleTimeRange> breakTimes
) {
    public PublicOperatingDay {
        businessHours = List.copyOf(businessHours);
        breakTimes = List.copyOf(breakTimes);
    }
}
