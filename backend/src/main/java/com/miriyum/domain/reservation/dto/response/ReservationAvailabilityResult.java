package com.miriyum.domain.reservation.dto.response;

/**
 * 입력 매장과 안정적으로 대응되는 예약 가능 판정 결과다.
 *
 * @param storeId 판정 대상 매장 ID
 * @param availability 예약 가능 상태
 */
public record ReservationAvailabilityResult(
        long storeId,
        ReservationAvailabilityStatus availability
) {

    public ReservationAvailabilityResult {
        if (storeId <= 0) {
            throw new IllegalArgumentException("storeId must be positive");
        }
        if (availability == null) {
            throw new IllegalArgumentException("availability must not be null");
        }
    }
}
