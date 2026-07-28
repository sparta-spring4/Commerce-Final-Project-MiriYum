package com.miriyum.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import java.util.stream.Stream;

class CommonErrorCodeTest {

    @ParameterizedTest
    @MethodSource("errorCodes")
    void 공통_오류_코드는_승인된_상태_코드와_메시지를_제공한다(
            CommonErrorCode errorCode,
            HttpStatus httpStatus,
            String code,
            String message
    ) {
        assertThat(errorCode.getHttpStatus()).isEqualTo(httpStatus);
        assertThat(errorCode.getCode()).isEqualTo(code);
        assertThat(errorCode.getMessage()).isEqualTo(message);
    }

    @Test
    void 공통_오류_코드는_중복되지_않는다() {
        assertThat(CommonErrorCode.values())
                .extracting(CommonErrorCode::getCode)
                .doesNotHaveDuplicates();
    }

    private static Stream<Arguments> errorCodes() {
        return Stream.of(
                Arguments.of(CommonErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                        "COMMON_001", "입력값이 올바르지 않습니다."),
                Arguments.of(CommonErrorCode.MALFORMED_REQUEST, HttpStatus.BAD_REQUEST,
                        "COMMON_002", "요청 본문을 읽을 수 없습니다."),
                Arguments.of(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST,
                        "COMMON_003", "Idempotency-Key 헤더가 필요합니다."),
                Arguments.of(CommonErrorCode.INVALID_IDEMPOTENCY_KEY, HttpStatus.BAD_REQUEST,
                        "COMMON_004", "Idempotency-Key 형식이 올바르지 않습니다."),
                Arguments.of(CommonErrorCode.ENDPOINT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "COMMON_005", "요청한 API 경로가 존재하지 않습니다."),
                Arguments.of(CommonErrorCode.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED,
                        "COMMON_006", "지원하지 않는 HTTP 메서드입니다."),
                Arguments.of(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.CONFLICT,
                        "COMMON_007", "동일한 Idempotency-Key를 다른 요청에 사용할 수 없습니다."),
                Arguments.of(CommonErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT,
                        "COMMON_008", "동시 요청 충돌로 처리하지 못했습니다. 다시 시도해 주세요."),
                Arguments.of(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "COMMON_009", "지원하지 않는 Content-Type입니다."),
                Arguments.of(CommonErrorCode.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS,
                        "COMMON_010", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of(CommonErrorCode.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                        "COMMON_011", "서버 내부 오류가 발생했습니다."),
                Arguments.of(CommonErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                        "COMMON_012", "서비스를 일시적으로 사용할 수 없습니다.")
        );
    }
}
