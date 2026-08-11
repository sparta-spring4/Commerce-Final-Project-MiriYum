package com.miriyum.domain.auth.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 계정 가입·본인 정보 관련 오류 코드다. 일반 사용자·매장 운영자 계정 모두 같은 카탈로그를 쓴다
 * ({@code docs/specs/auth-account/spec.md}의 {@code ACCOUNT_###}).
 */
public enum AccountErrorCode implements ErrorCode {

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "ACCOUNT_001", "이미 가입된 이메일입니다."),
    PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, "ACCOUNT_002", "이미 가입된 휴대전화 번호입니다."),
    EMAIL_VERIFICATION_REFERENCE_INVALID(HttpStatus.BAD_REQUEST, "ACCOUNT_003", "이메일 확인 참조가 없거나 유효하지 않습니다."),
    IDENTITY_VERIFICATION_REFERENCE_INVALID(HttpStatus.BAD_REQUEST, "ACCOUNT_004", "본인확인 참조가 없거나 유효하지 않습니다."),
    NICKNAME_CHANGE_TOO_SOON(HttpStatus.CONFLICT, "ACCOUNT_005", "닉네임은 변경 가능 시각 전에 다시 변경할 수 없습니다."),
    RESERVATION_CONTACT_REQUIRED(HttpStatus.CONFLICT, "ACCOUNT_006", "예약 연락처가 등록되지 않았습니다."),
    CONTACT_CHANGE_NOT_ALLOWED(HttpStatus.CONFLICT, "ACCOUNT_007", "최초 등록한 연락처는 변경할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    AccountErrorCode(HttpStatus httpStatus, String code, String message) {
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
