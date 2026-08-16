package com.miriyum.domain.reservation.waiting.dto;

public record WaitingSettingCommandResult(int httpStatus, Object data) {
    public WaitingSettingCommandResult {
        if (httpStatus != 200 && httpStatus != 202) {
            throw new IllegalArgumentException("waiting setting command status must be 200 or 202");
        }
    }
}
