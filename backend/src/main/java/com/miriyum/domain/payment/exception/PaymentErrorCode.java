package com.miriyum.domain.payment.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Payment 도메인이 소유하는 외부 오류 계약이다. */
public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_001", "결제를 찾을 수 없습니다."),
    INVALID_STATE_TRANSITION(
            HttpStatus.CONFLICT,
            "PAYMENT_002",
            "현재 결제 상태에서는 요청을 처리할 수 없습니다."
    ),
    PROVIDER_MAPPING_MISMATCH(
            HttpStatus.CONFLICT,
            "PAYMENT_003",
            "결제 검증 정보가 준비된 거래와 일치하지 않습니다."
    ),
    ACTIVE_SOURCE_CONFLICT(
            HttpStatus.CONFLICT,
            "PAYMENT_004",
            "같은 예약 거래에 처리 중인 결제가 있습니다."
    ),
    INVALID_HISTORY_CURSOR(
            HttpStatus.BAD_REQUEST,
            "PAYMENT_005",
            "결제 이력 커서가 올바르지 않습니다."
    ),
    INVALID_WEBHOOK_SIGNATURE(
            HttpStatus.UNAUTHORIZED,
            "PAYMENT_006",
            "유효한 결제 Webhook이 아닙니다."
    ),
    REFUND_AMOUNT_EXCEEDED(
            HttpStatus.CONFLICT,
            "PAYMENT_007",
            "환불 가능 금액을 초과했습니다."
    ),
    SOURCE_EXPIRED(
            HttpStatus.CONFLICT,
            "PAYMENT_008",
            "결제 제공자 결과를 확인할 수 없습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    PaymentErrorCode(HttpStatus httpStatus, String code, String message) {
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
