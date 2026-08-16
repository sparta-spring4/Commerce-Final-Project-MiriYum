package com.miriyum.domain.reservation.waiting.dto;

/**
 * 설정 교체 HTTP 상태와 공개 payload를 서비스 경계에서 함께 전달한다.
 *
 * @param httpStatus 일반 설정 교체의 200 또는 closure job 생성의 202
 * @param data 설정 snapshot 또는 closure job snapshot
 */
public record WaitingSettingCommandResult(int httpStatus, Object data) {
    public WaitingSettingCommandResult {
        if (httpStatus != 200 && httpStatus != 202) {
            throw new IllegalArgumentException("waiting setting command status must be 200 or 202");
        }
    }
}
