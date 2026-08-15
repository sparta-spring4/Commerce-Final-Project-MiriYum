package com.miriyum.domain.platformoperator.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 플랫폼 운영자 공통 인가·재인증의 외부 오류 계약이다. */
public enum AdminAuthorizationErrorCode implements ErrorCode {
    AUTHORIZATION_DENIED(HttpStatus.FORBIDDEN, "ADMIN_001", "요청한 운영 명령을 승인할 수 없습니다."),
    REAUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "ADMIN_002", "현재 비밀번호 재인증에 실패했습니다."),
    LAST_SUPER_ADMIN_REQUIRED(HttpStatus.CONFLICT, "ADMIN_003", "유일한 슈퍼관리자는 변경하거나 교체할 수 없습니다."),
    FORBIDDEN_AUTHORITY(HttpStatus.BAD_REQUEST, "ADMIN_004", "하위 운영자에게 부여할 수 없는 역할 또는 권한입니다."),
    DUPLICATE_OPERATOR_EMAIL(HttpStatus.CONFLICT, "ADMIN_005", "이미 등록된 운영자 이메일입니다."),
    AUDIT_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_006", "감사 사건을 찾을 수 없습니다."),
    AUDIT_CORRECTION_CONFLICT(HttpStatus.CONFLICT, "ADMIN_007", "이미 보정 사건이 연결되어 있습니다.");

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
