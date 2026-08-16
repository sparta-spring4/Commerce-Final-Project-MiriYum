package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;

/** QR 방문 완료와 운영자 노쇼 명령의 저장·재생 가능한 결과다. */
public record ReservationVisitCommandResult(
        int httpStatus,
        ReservationDetailResponse data
) {
}
