package com.miriyum.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ServiceExceptionTest {

    @Test
    void 도메인_오류_코드를_보존한다() {
        ServiceException exception = new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
        assertThat(exception.getMessage()).isEqualTo("동시 요청 충돌로 처리하지 못했습니다. 다시 시도해 주세요.");
    }

    @Test
    void 오류_코드는_필수다() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ServiceException(null))
                .withMessage("errorCode must not be null");
    }
}
