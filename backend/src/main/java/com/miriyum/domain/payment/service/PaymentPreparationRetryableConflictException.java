package com.miriyum.domain.payment.service;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;

/**
 * caller transaction 전체가 종료된 뒤 예약금 준비 명령을 제한 재진입할 수 있음을 알린다.
 *
 * <p>Payment 내부의 DB 원인과 constraint 정보는 공개하지 않고 기존 COMMON_008 의미만
 * 유지한다.</p>
 */
public final class PaymentPreparationRetryableConflictException extends ServiceException {

    public PaymentPreparationRetryableConflictException() {
        super(CommonErrorCode.CONCURRENT_MODIFICATION);
    }
}
