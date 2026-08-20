package com.miriyum.domain.platformoperator.adminmonitoring.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AdminMonitoringErrorCode implements ErrorCode {
    INVALID_MONITORING_FILTER(HttpStatus.BAD_REQUEST, "MONITORING_001", "잘못된 모니터링 필터입니다."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "MONITORING_002", "잘못된 cursor입니다."),
    EXPIRED_CURSOR(HttpStatus.BAD_REQUEST, "MONITORING_003", "만료된 cursor입니다."),
    MONITORING_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "MONITORING_004", "모니터링 사건을 찾을 수 없습니다."),
    MONITORING_SOURCES_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MONITORING_005",
            "모니터링 원장을 현재 조회할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    AdminMonitoringErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}
