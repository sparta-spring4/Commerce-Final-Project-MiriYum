package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;

/**
 * 입력 매장과 대응되는 실제 서비스·점유 시간 계산 결과다.
 *
 * @param storeId 대상 매장 ID
 * @param status 계산 상태
 * @param timeSnapshot 계산 성공 시 시간 정책 스냅샷, 실패 폐쇄 시 {@code null}
 */
public record ReservationTimeResolutionResult(
        long storeId,
        ReservationTimeResolutionStatus status,
        ReservationTimeSnapshot timeSnapshot
) {

    public ReservationTimeResolutionResult {
        if (storeId <= 0 || status == null) {
            throw new IllegalArgumentException("valid storeId and status are required");
        }
        boolean resolved = status == ReservationTimeResolutionStatus.RESOLVED;
        if ((resolved && timeSnapshot == null) || (!resolved && timeSnapshot != null)) {
            throw new IllegalArgumentException("inconsistent reservation time result");
        }
    }

    public static ReservationTimeResolutionResult resolved(
            long storeId,
            ReservationTimeSnapshot timeSnapshot
    ) {
        return new ReservationTimeResolutionResult(
                storeId,
                ReservationTimeResolutionStatus.RESOLVED,
                timeSnapshot
        );
    }

    public static ReservationTimeResolutionResult unavailable(long storeId) {
        return new ReservationTimeResolutionResult(
                storeId,
                ReservationTimeResolutionStatus.UNAVAILABLE,
                null
        );
    }
}
