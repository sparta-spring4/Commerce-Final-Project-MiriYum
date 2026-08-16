package com.miriyum.domain.auth.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * JWT·세션 관련 오류 코드다. {@code docs/specs/auth-account/spec.md}의 {@code AUTH_###}
 * 카탈로그와 일치하며, 일반 사용자·매장 운영자 모두 같은 카탈로그를 쓴다.
 */
public enum AuthErrorCode implements ErrorCode {

    ACCESS_TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH_001", "Access Token이 필요합니다."),
    ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", "Access Token이 만료됐습니다."),
    ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", "Access Token 형식 또는 서명이 유효하지 않습니다."),
    TOKEN_NAMESPACE_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH_004", "토큰 namespace가 요청한 API와 일치하지 않습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_005", "이메일 또는 비밀번호가 올바르지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH_006", "인증됐지만 요청 권한이 없습니다."),
    REFRESH_TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH_007", "Refresh Token 쿠키가 필요합니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_008", "Refresh Token이 만료됐거나 유효하지 않습니다."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "AUTH_009", "CSRF 토큰 검증에 실패했습니다."),
    ORIGIN_REJECTED(HttpStatus.FORBIDDEN, "AUTH_010", "Origin 또는 Referer 검증에 실패했습니다."),
    ACCOUNT_RESTRICTED(HttpStatus.FORBIDDEN, "AUTH_011", "현재 계정 상태로는 이용할 수 없습니다."),
    INITIAL_PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "AUTH_012", "최초 비밀번호 변경이 필요합니다."),
    KAKAO_OAUTH_INVALID(HttpStatus.BAD_REQUEST, "AUTH_013", "카카오 로그인 요청이 유효하지 않습니다."),
    KAKAO_ALREADY_LINKED(HttpStatus.CONFLICT, "AUTH_014", "해당 카카오 계정은 다른 계정에 연결돼 있습니다."),
    PLATFORM_OPERATOR_SESSION_INVALID(
            HttpStatus.UNAUTHORIZED,
            "AUTH_015",
            "플랫폼 운영자 세션이 더 이상 유효하지 않습니다."),
    QR_EPOCH_STALE(HttpStatus.CONFLICT, "AUTH_017", "QR 계정 세대가 현재 값과 일치하지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    AuthErrorCode(HttpStatus httpStatus, String code, String message) {
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
