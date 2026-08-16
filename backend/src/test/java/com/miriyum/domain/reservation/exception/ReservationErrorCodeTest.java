package com.miriyum.domain.reservation.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

class ReservationErrorCodeTest {

    @ParameterizedTest
    @MethodSource("errorCodes")
    @DisplayName("예약 오류 코드는 승인된 HTTP 상태와 외부 코드를 제공한다")
    void providesApprovedErrorContract(
            ReservationErrorCode errorCode,
            HttpStatus httpStatus,
            String code,
            String message
    ) {
        // then
        assertThat(errorCode.getHttpStatus()).isEqualTo(httpStatus);
        assertThat(errorCode.getCode()).isEqualTo(code);
        assertThat(errorCode.getMessage()).isEqualTo(message);
    }

    @Test
    @DisplayName("예약 오류 외부 코드는 중복되지 않는다")
    void doesNotContainDuplicateExternalCodes() {
        // when & then
        assertThat(ReservationErrorCode.values()).hasSize(21);
        assertThat(ReservationErrorCode.values())
                .extracting(ReservationErrorCode::getCode)
                .doesNotHaveDuplicates();
    }

    private static Stream<Arguments> errorCodes() {
        return Stream.of(
                Arguments.of(ReservationErrorCode.RESERVATION_NOT_FOUND,
                        HttpStatus.NOT_FOUND, "RESERVATION_001", "예약을 찾을 수 없습니다."),
                Arguments.of(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW,
                        HttpStatus.CONFLICT, "RESERVATION_002", "요청 시간이 영업·예약 접수 구간에 없습니다."),
                Arguments.of(ReservationErrorCode.INSUFFICIENT_CAPACITY,
                        HttpStatus.CONFLICT, "RESERVATION_003", "요청한 시간의 예약 수용량이 부족합니다."),
                Arguments.of(ReservationErrorCode.DUPLICATE_RESERVATION,
                        HttpStatus.CONFLICT, "RESERVATION_004", "같은 사용자·매장에 겹치는 활성 예약이 있습니다."),
                Arguments.of(ReservationErrorCode.INVALID_STATE_TRANSITION,
                        HttpStatus.CONFLICT, "RESERVATION_005", "현재 예약 상태에서 처리할 수 없습니다."),
                Arguments.of(ReservationErrorCode.CANCELLATION_NOT_ALLOWED,
                        HttpStatus.CONFLICT, "RESERVATION_006", "현재 시각·정책에서는 예약을 취소할 수 없습니다."),
                Arguments.of(ReservationErrorCode.CAPACITY_POLICY_CHANGED,
                        HttpStatus.CONFLICT, "RESERVATION_007", "조회 후 정책·수용량 버전이 변경되었습니다."),
                Arguments.of(ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT,
                        HttpStatus.CONFLICT, "RESERVATION_008", "현재 예약 점유와 수용량 설정이 충돌합니다."),
                Arguments.of(ReservationErrorCode.PARTY_SIZE_OUT_OF_RANGE,
                        HttpStatus.CONFLICT, "RESERVATION_009", "요청 인원이 매장 최소·최대 정책을 벗어났습니다."),
                Arguments.of(ReservationErrorCode.TIME_POLICY_CONFLICT,
                        HttpStatus.CONFLICT, "RESERVATION_010",
                        "현재 시간 정책 상태에서 요청한 작업을 수행할 수 없습니다."),
                Arguments.of(ReservationErrorCode.WAITING_SETTING_VERSION_CONFLICT,
                        HttpStatus.CONFLICT, "WAITING_001",
                        "조회 후 웨이팅 설정 버전이 변경되었습니다."),
                Arguments.of(ReservationErrorCode.WAITING_DISABLE_ACTION_REQUIRED,
                        HttpStatus.CONFLICT, "WAITING_002",
                        "활성 팀이 있으면 비활성화 처리 방법이 필요합니다."),
                Arguments.of(ReservationErrorCode.WAITING_TEAM_NOT_FOUND,
                        HttpStatus.NOT_FOUND, "WAITING_003",
                        "대상 매장 범위의 웨이팅 팀을 찾을 수 없습니다."),
                Arguments.of(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND,
                        HttpStatus.NOT_FOUND, "WAITING_004",
                        "대상 매장 범위의 웨이팅 종결 작업을 찾을 수 없습니다."),
                Arguments.of(ReservationErrorCode.WAITING_VERSION_CONFLICT,
                        HttpStatus.CONFLICT, "WAITING_005",
                        "대상 팀 버전이 요청 버전과 다릅니다."),
                Arguments.of(ReservationErrorCode.WAITING_INVALID_TRANSITION,
                        HttpStatus.CONFLICT, "WAITING_006",
                        "현재 상태에서는 요청한 웨이팅 전이를 수행할 수 없습니다."),
                Arguments.of(ReservationErrorCode.WAITING_NOT_FIFO_HEAD,
                        HttpStatus.CONFLICT, "WAITING_007",
                        "호출 대상이 활성 FIFO 선두가 아닙니다."),
                Arguments.of(ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT,
                        HttpStatus.CONFLICT, "WAITING_008",
                        "활성 웨이팅 멤버십과 요청 전제가 충돌합니다."),
                Arguments.of(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS,
                        HttpStatus.CONFLICT, "WAITING_011",
                        "계정에 이미 활성 웨이팅이 있습니다. 기존 웨이팅을 종료한 후 다시 시도해 주세요."),
                Arguments.of(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_READY,
                        HttpStatus.CONFLICT, "WAITING_009",
                        "웨이팅 종결 작업이 아직 완료되지 않았거나 재확인이 필요합니다."),
                Arguments.of(ReservationErrorCode.WAITING_CLOSE_JOB_ITEM_FAILED,
                        HttpStatus.CONFLICT, "WAITING_010",
                        "웨이팅 종결 작업 항목 처리 중 실패가 발생했습니다.")
        );
    }
}
