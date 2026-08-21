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
    ),
    TIME_POLICY_CONFLICT(
            HttpStatus.CONFLICT,
            "RESERVATION_010",
            "현재 시간 정책 상태에서 요청한 작업을 수행할 수 없습니다."
    ),
    CHECK_IN_QR_UNAVAILABLE(
            HttpStatus.CONFLICT,
            "RESERVATION_011",
            "사용할 수 없는 체크인 QR입니다."
    ),
    OUTSIDE_CHECK_IN_WINDOW(
            HttpStatus.CONFLICT,
            "RESERVATION_012",
            "현재는 예약 체크인을 완료할 수 없습니다."
    ),
    NO_SHOW_TOO_EARLY(
            HttpStatus.CONFLICT,
            "RESERVATION_013",
            "아직 노쇼를 확정할 수 없습니다."
    ),
    RESERVATION_MONITORING_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "RESERVATION_014",
            "예약 모니터링 원장을 조회할 수 없습니다."
    ),
    WAITING_SETTING_VERSION_CONFLICT(
            HttpStatus.CONFLICT,
            "WAITING_001",
            "조회 후 웨이팅 설정 버전이 변경되었습니다."
    ),
    WAITING_DISABLE_ACTION_REQUIRED(
            HttpStatus.CONFLICT,
            "WAITING_002",
            "활성 팀이 있으면 비활성화 처리 방법이 필요합니다."
    ),
    WAITING_TEAM_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "WAITING_003",
            "대상 매장 범위의 웨이팅 팀을 찾을 수 없습니다."
    ),
    WAITING_CLOSE_JOB_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "WAITING_004",
            "대상 매장 범위의 웨이팅 종결 작업을 찾을 수 없습니다."
    ),
    WAITING_VERSION_CONFLICT(
            HttpStatus.CONFLICT,
            "WAITING_005",
            "대상 팀 버전이 요청 버전과 다릅니다."
    ),
    WAITING_INVALID_TRANSITION(
            HttpStatus.CONFLICT,
            "WAITING_006",
            "현재 상태에서는 요청한 웨이팅 전이를 수행할 수 없습니다."
    ),
    WAITING_NOT_FIFO_HEAD(
            HttpStatus.CONFLICT,
            "WAITING_007",
            "호출 대상이 활성 FIFO 선두가 아닙니다."
    ),
    WAITING_ACTIVE_MEMBERSHIP_CONFLICT(
            HttpStatus.CONFLICT,
            "WAITING_008",
            "활성 웨이팅 멤버십과 요청 전제가 충돌합니다."
    ),
    ACCOUNT_ACTIVE_WAITING_EXISTS(
            HttpStatus.CONFLICT,
            "WAITING_011",
            "계정에 이미 활성 웨이팅이 있습니다. 기존 웨이팅을 종료한 후 다시 시도해 주세요."
    ),
    WAITING_CLOSE_JOB_NOT_READY(
            HttpStatus.CONFLICT,
            "WAITING_009",
            "웨이팅 종결 작업이 아직 완료되지 않았거나 재확인이 필요합니다."
    ),
    WAITING_CLOSE_JOB_ITEM_FAILED(
            HttpStatus.CONFLICT,
            "WAITING_010",
            "웨이팅 종결 작업 항목 처리 중 실패가 발생했습니다."
    ),
    WAITING_RECEPTION_CLOSED(
            HttpStatus.CONFLICT,
            "WAITING_012",
            "현재 매장은 신규 웨이팅 접수를 받지 않습니다."
    ),
    LOCATION_PROOF_INVALID(
            HttpStatus.CONFLICT,
            "WAITING_013",
            "사용할 수 없는 위치 증명입니다."
    ),
    PARTY_INVITATION_INVALID(
            HttpStatus.CONFLICT,
            "WAITING_014",
            "사용할 수 없는 일행 초대입니다."
    ),
    PARTY_MUTATION_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "WAITING_015",
            "현재 웨이팅 상태에서는 일행 구성을 변경할 수 없습니다."
    ),
    REPRESENTATIVE_TRANSFER_INVALID(
            HttpStatus.CONFLICT,
            "WAITING_016",
            "사용할 수 없는 대표자 이전 제안입니다."
    ),
    PARTY_CAPACITY_EXCEEDED(
            HttpStatus.CONFLICT,
            "WAITING_017",
            "일행 참여 인원이 등록 인원을 초과합니다."
    ),
    WAITING_MONITORING_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "WAITING_018",
            "웨이팅 모니터링 원장을 조회할 수 없습니다."
    ),
    WAITING_HISTORY_CURSOR_INVALID(
            HttpStatus.BAD_REQUEST,
            "WAITING_019",
            "웨이팅 이력 cursor가 올바르지 않습니다."
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
