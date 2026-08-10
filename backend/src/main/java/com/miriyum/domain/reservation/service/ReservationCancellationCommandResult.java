package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;

/** 예약 취소 명령의 HTTP 상태와 저장·재생된 상세 data 결과다. */
public record ReservationCancellationCommandResult(
        int httpStatus,
        ReservationDetailResponse data
) {
}
