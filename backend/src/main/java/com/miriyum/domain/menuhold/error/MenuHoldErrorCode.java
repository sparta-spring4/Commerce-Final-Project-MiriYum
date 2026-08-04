package com.miriyum.domain.menuhold.error;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MenuHoldErrorCode implements ErrorCode {
    INELIGIBLE_MENU(HttpStatus.CONFLICT, "MENU_HOLD_001", "현재 메뉴 상태 또는 자격에서 홀드할 수 없습니다."),
    INSUFFICIENT_QUANTITY(HttpStatus.CONFLICT, "MENU_HOLD_002", "요청한 구간의 온라인 메뉴 수량이 부족합니다."),
    BUCKET_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU_HOLD_003", "메뉴 수량 버킷을 찾을 수 없습니다."),
    POOL_ALLOCATION_EXCEEDS_SUPPLY(
            HttpStatus.CONFLICT,
            "MENU_HOLD_004",
            "수량 풀 배분 합계가 총 공급 수량을 초과합니다."),
    QUANTITY_IN_USE(HttpStatus.CONFLICT, "MENU_HOLD_005", "이미 사용 중인 수량보다 작게 줄일 수 없습니다."),
    INVENTORY_STATE_CONFLICT(HttpStatus.CONFLICT, "MENU_HOLD_006", "현재 수량 상태에서 요청한 전이를 수행할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    MenuHoldErrorCode(HttpStatus httpStatus, String code, String message) {
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
