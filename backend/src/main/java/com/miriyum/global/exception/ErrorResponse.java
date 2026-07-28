package com.miriyum.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 모든 API 오류가 따르는 응답 본문이다.
 */
public record ErrorResponse(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ValidationErrorDetail> details
) {

    public ErrorResponse {
        details = details == null ? null : List.copyOf(details);
    }

    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, List<ValidationErrorDetail> details) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), details);
    }
}
