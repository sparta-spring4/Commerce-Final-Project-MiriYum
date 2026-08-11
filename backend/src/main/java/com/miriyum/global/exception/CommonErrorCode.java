package com.miriyum.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 특정 도메인에 속하지 않는 공통 오류 코드다.
 */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_002", "요청 본문을 읽을 수 없습니다."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "COMMON_003", "Idempotency-Key 헤더가 필요합니다."),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "COMMON_004", "Idempotency-Key 형식이 올바르지 않습니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_005", "요청한 API 경로가 존재하지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_006", "지원하지 않는 HTTP 메서드입니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "COMMON_007", "동일한 Idempotency-Key를 다른 요청에 사용할 수 없습니다."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "COMMON_008", "동시 요청 충돌로 처리하지 못했습니다. 다시 시도해 주세요."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON_009", "지원하지 않는 Content-Type입니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "COMMON_010", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_011", "서버 내부 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_012", "서비스를 일시적으로 사용할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    CommonErrorCode(HttpStatus httpStatus, String code, String message) {
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
