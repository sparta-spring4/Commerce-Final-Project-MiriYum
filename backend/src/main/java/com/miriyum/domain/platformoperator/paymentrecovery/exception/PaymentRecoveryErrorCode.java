package com.miriyum.domain.platformoperator.paymentrecovery.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PaymentRecoveryErrorCode implements ErrorCode {
    RECOVERY_CASE_NOT_FOUND(
            HttpStatus.NOT_FOUND, "PAYMENT_RECOVERY_001", "결제 복구 사건을 찾을 수 없습니다."),
    RECOVERY_CASE_STATE_CONFLICT(
            HttpStatus.CONFLICT, "PAYMENT_RECOVERY_002", "결제 복구 사건 상태 또는 버전이 변경되었습니다."),
    RECOVERY_PROPOSAL_NOT_FOUND(
            HttpStatus.NOT_FOUND, "PAYMENT_RECOVERY_003", "결제 복구 제안을 찾을 수 없습니다."),
    RECOVERY_ACTION_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST, "PAYMENT_RECOVERY_004", "현재 사건에서 허용되지 않은 복구 명령입니다."),
    RECOVERY_APPROVER_MUST_DIFFER(
            HttpStatus.FORBIDDEN, "PAYMENT_RECOVERY_005", "추가 승인자는 요청자와 달라야 합니다."),
    RECOVERY_COMPENSATION_NOT_SUPPORTED(
            HttpStatus.BAD_REQUEST, "PAYMENT_RECOVERY_006", "별도 보상 지급 계약은 아직 지원되지 않습니다."),
    RECOVERY_EXECUTION_NOT_FOUND(
            HttpStatus.NOT_FOUND, "PAYMENT_RECOVERY_007", "결제 복구 실행을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    PaymentRecoveryErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return status;
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
