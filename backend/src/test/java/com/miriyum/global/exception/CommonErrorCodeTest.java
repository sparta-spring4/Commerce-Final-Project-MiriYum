package com.miriyum.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CommonErrorCodeTest {

    @Test
    void 공통_오류_코드는_상태_코드와_메시지를_제공한다() {
        assertThat(CommonErrorCode.VALIDATION_FAILED.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(CommonErrorCode.VALIDATION_FAILED.getCode()).isEqualTo("COMMON_001");
        assertThat(CommonErrorCode.VALIDATION_FAILED.getMessage())
                .isEqualTo("입력값이 올바르지 않습니다.");
    }

    @Test
    void 공통_오류_코드는_중복되지_않는다() {
        assertThat(CommonErrorCode.values())
                .extracting(CommonErrorCode::getCode)
                .doesNotHaveDuplicates();
    }
}
