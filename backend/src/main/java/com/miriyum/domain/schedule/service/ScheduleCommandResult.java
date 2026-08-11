package com.miriyum.domain.schedule.service;

public record ScheduleCommandResult<T>(
        int httpStatus,
        T data
) {
}
