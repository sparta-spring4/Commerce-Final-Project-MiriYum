package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;

/** 예약 생성 facade가 HTTP 성공 상태와 기존 상세 응답을 전달하는 최소 결과다. */
public record ReservationCreationCommandResult(
        int httpStatus,
        ReservationDetailResponse data
) {
}
