package com.miriyum.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트에 노출할 오류 계약이다.
 *
 * <p>각 도메인은 이 인터페이스를 구현해 자체 오류 코드를 정의한다.</p>
 */
public interface ErrorCode {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();
}
