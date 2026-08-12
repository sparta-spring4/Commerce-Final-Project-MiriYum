package com.miriyum.domain.schedule.dto.contract;

import java.time.LocalTime;

/** 공개 조회에 필요한 일정 시간 구간이다. */
public record PublicScheduleTimeRange(LocalTime startTime, LocalTime endTime) {
}
