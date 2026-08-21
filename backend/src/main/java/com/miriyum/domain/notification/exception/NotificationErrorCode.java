package com.miriyum.domain.notification.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum NotificationErrorCode implements ErrorCode {

    INVALID_HISTORY_CURSOR(
            HttpStatus.BAD_REQUEST,
            "NOTIFICATION_001",
            "알림 이력 커서가 올바르지 않습니다."
    ),
    SOURCE_EVENT_CONFLICT(
            HttpStatus.CONFLICT,
            "NOTIFICATION_002",
            "동일한 알림 원 사건 식별자를 다른 내용으로 사용할 수 없습니다."
    ),
    NOTIFICATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "NOTIFICATION_003",
            "알림을 찾을 수 없습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    NotificationErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
