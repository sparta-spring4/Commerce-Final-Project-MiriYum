package com.miriyum.domain.pickup.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 픽업 예약 도메인이 소유하는 승인된 외부 오류 코드다. */
public enum PickupErrorCode implements ErrorCode {
    PICKUP_NOT_FOUND(HttpStatus.NOT_FOUND, "PICKUP_001", "픽업 예약을 찾을 수 없습니다."),
    TRANSACTION_NOT_ELIGIBLE(HttpStatus.CONFLICT, "PICKUP_002", "픽업 거래 상태가 유효하지 않습니다."),
    SLOT_NOT_AVAILABLE(HttpStatus.CONFLICT, "PICKUP_003", "요청한 픽업 제공 구간을 사용할 수 없습니다."),
    INSUFFICIENT_QUANTITY(HttpStatus.CONFLICT, "PICKUP_004", "픽업 메뉴 수량이 부족합니다."),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "PICKUP_005", "현재 픽업 상태에서 처리할 수 없습니다."),
    CANCELLATION_NOT_ALLOWED(HttpStatus.CONFLICT, "PICKUP_006", "현재 시각에는 픽업을 취소할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    PickupErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() { return httpStatus; }

    @Override
    public String getCode() { return code; }

    @Override
    public String getMessage() { return message; }
}
