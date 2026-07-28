package com.miriyum.global.exception;

/**
 * 요청 검증에 실패한 위치와 사유다.
 */
public record ValidationErrorDetail(
        String field,
        String reason
) {
}
