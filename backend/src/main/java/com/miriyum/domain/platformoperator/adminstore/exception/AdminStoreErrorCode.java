package com.miriyum.domain.platformoperator.adminstore.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AdminStoreErrorCode implements ErrorCode {
    STORE_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_STORE_001", "매장 제재 사건을 찾을 수 없습니다."),
    STORE_CASE_STATE_CONFLICT(HttpStatus.CONFLICT, "ADMIN_STORE_002", "매장 제재 사건 상태가 변경되었습니다."),
    SANCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_STORE_003", "매장 제재를 찾을 수 없습니다."),
    SANCTION_STATE_CONFLICT(HttpStatus.CONFLICT, "ADMIN_STORE_004", "매장 제재 상태가 변경되었습니다."),
    IMPACT_CONFIRMATION_REQUIRED(HttpStatus.CONFLICT, "ADMIN_STORE_005", "현재 사건과 일치하는 거래 영향 확인이 필요합니다."),
    POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "ADMIN_STORE_006", "ADMIN-007 제재 정책에 맞지 않는 요청입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
    AdminStoreErrorCode(HttpStatus status, String code, String message) {
        this.status = status; this.code = code; this.message = message;
    }
    @Override public HttpStatus getHttpStatus() { return status; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}
