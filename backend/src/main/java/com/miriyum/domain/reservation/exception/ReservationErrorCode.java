package com.miriyum.domain.reservation.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 일반 예약 도메인이 소유하는 외부 오류 코드다.
 */
public enum ReservationErrorCode implements ErrorCode {

    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_001", "예약을 찾을 수 없습니다."),
    OUTSIDE_RESERVATION_WINDOW(
            HttpStatus.CONFLICT,
            "RESERVATION_002",
            "요청 시간이 영업·예약 접수 구간에 없습니다."
    ),
    INSUFFICIENT_CAPACITY(
            HttpStatus.CONFLICT,
            "RESERVATION_003",
            "요청한 시간의 예약 수용량이 부족합니다."
    ),
    DUPLICATE_RESERVATION(
            HttpStatus.CONFLICT,
            "RESERVATION_004",
            "같은 사용자·매장에 겹치는 활성 예약이 있습니다."
    ),
    INVALID_STATE_TRANSITION(
            HttpStatus.CONFLICT,
            "RESERVATION_005",
            "현재 예약 상태에서 처리할 수 없습니다."
    ),
    CANCELLATION_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "RESERVATION_006",
            "현재 시각·정책에서는 예약을 취소할 수 없습니다."
    ),
    CAPACITY_POLICY_CHANGED(
            HttpStatus.CONFLICT,
            "RESERVATION_007",
            "조회 후 정책·수용량 버전이 변경되었습니다."
    ),
    CAPACITY_CONFIGURATION_CONFLICT(
            HttpStatus.CONFLICT,
            "RESERVATION_008",
            "현재 예약 점유와 수용량 설정이 충돌합니다."
    ),
    PARTY_SIZE_OUT_OF_RANGE(
            HttpStatus.CONFLICT,
            "RESERVATION_009",
            "요청 인원이 매장 최소·최대 정책을 벗어났습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ReservationErrorCode(HttpStatus httpStatus, String code, String message) {
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
