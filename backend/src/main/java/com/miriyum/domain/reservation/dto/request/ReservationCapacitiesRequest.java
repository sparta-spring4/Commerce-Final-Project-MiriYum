package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 업무 날짜에 적용할 예약 수용량 버킷 전체 목록이다.
 *
 * @param buckets 일부 병합하지 않고 새 정책 버전으로 게시할 전체 버킷
 */
public record ReservationCapacitiesRequest(
        @NotNull
        @Size(min = 1, max = 96)
        List<@NotNull @Valid CapacityBucketRequest> buckets
) {
}
