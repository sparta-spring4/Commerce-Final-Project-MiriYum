package com.miriyum.domain.store.error;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 매장·일정·메뉴 도메인이 외부에 노출하는 오류 계약이다.
 */
public enum StoreErrorCode implements ErrorCode {

    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_001", "매장을 찾을 수 없습니다."),
    BUSINESS_NUMBER_CONFLICT(HttpStatus.CONFLICT, "STORE_002", "이미 등록된 사업자등록번호입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "STORE_003", "대상 매장의 대표 운영자가 아닙니다."),
    CATALOG_CODE_INVALID(HttpStatus.BAD_REQUEST, "STORE_004", "승인되지 않은 카테고리 또는 태그 코드입니다."),
    STORE_STATE_CONFLICT(HttpStatus.CONFLICT, "STORE_005", "현재 매장 상태에서 요청한 작업을 수행할 수 없습니다."),
    SCHEDULE_CONFLICT(HttpStatus.CONFLICT, "STORE_006", "영업 또는 예약 접수 시간대가 충돌합니다."),
    VERIFICATION_STATE_CONFLICT(HttpStatus.CONFLICT, "STORE_007", "현재 입점 검증 상태에서 운영할 수 없습니다."),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_009", "메뉴를 찾을 수 없습니다."),
    MENU_STATE_CONFLICT(HttpStatus.CONFLICT, "STORE_010", "현재 메뉴 상태에서 요청한 전이를 수행할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    StoreErrorCode(HttpStatus httpStatus, String code, String message) {
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
