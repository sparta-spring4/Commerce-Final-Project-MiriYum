package com.miriyum.global.exception;

import java.util.Objects;

/**
 * 예상 가능한 서비스·도메인 오류를 전역 예외 처리기로 전달한다.
 */
public class ServiceException extends RuntimeException {

    private final ErrorCode errorCode;

    public ServiceException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
