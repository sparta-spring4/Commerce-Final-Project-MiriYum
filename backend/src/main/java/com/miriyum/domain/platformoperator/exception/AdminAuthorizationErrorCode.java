package com.miriyum.domain.platformoperator.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 플랫폼 운영자 공통 인가·재인증의 외부 오류 계약이다. */
public enum AdminAuthorizationErrorCode implements ErrorCode {
    AUTHORIZATION_DENIED(HttpStatus.FORBIDDEN, "ADMIN_001", "요청한 운영 명령을 승인할 수 없습니다."),
    REAUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "ADMIN_002", "현재 비밀번호 재인증에 실패했습니다."),
    LAST_SUPER_ADMIN_REQUIRED(HttpStatus.CONFLICT, "ADMIN_003", "마지막 슈퍼관리자는 제거할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    AdminAuthorizationErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getHttpStatus() { return httpStatus; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}
