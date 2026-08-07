package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 예약 인원 구성이다. 영유아를 포함한 전체 인원은 수용량 계산에 사용된다.
 *
 * @param adultCount 성인 인원
 * @param childCount 아동 인원
 * @param infantCount 영유아 인원
 */
public record ReservationPartyRequest(
        @NotNull @Min(0) @Max(100) Integer adultCount,
        @NotNull @Min(0) @Max(100) Integer childCount,
        @NotNull @Min(0) @Max(100) Integer infantCount
) {

    /**
     * 수용량에 반영할 전체 인원을 반환한다.
     *
     * @return 성인·아동·영유아 인원의 합
     * @throws IllegalStateException 인원 검증 전 누락된 값이 있을 때
     */
    public int totalCount() {
        if (!hasAllCounts()) {
            throw new IllegalStateException("all party counts are required");
        }
        return adultCount + childCount + infantCount;
    }

    @AssertTrue
    public boolean isTotalCountValid() {
        return hasAllCounts() && totalCount() >= 1;
    }

    private boolean hasAllCounts() {
        return adultCount != null && childCount != null && infantCount != null;
    }
}
