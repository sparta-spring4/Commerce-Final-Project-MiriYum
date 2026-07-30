package com.miriyum.domain.store.schedule.service;

public record ScheduleCommandResult<T>(
        int httpStatus,
        T data
) {
}
