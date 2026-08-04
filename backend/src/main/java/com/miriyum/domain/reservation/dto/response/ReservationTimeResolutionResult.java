package com.miriyum.domain.reservation.dto.response;

/**
 * 입력 매장과 대응되는 실제 서비스·점유 시간 계산 결과다.
 *
 * @param storeId 대상 매장 ID
 * @param status 계산 상태
 * @param time 계산 성공 시 순수 scalar 결과, 실패 폐쇄 시 {@code null}
 */
public record ReservationTimeResolutionResult(
        long storeId,
        ReservationTimeResolutionStatus status,
        ResolvedReservationTime time
) {

    public ReservationTimeResolutionResult {
        if (storeId <= 0 || status == null) {
            throw new IllegalArgumentException("valid storeId and status are required");
        }
        boolean resolved = status == ReservationTimeResolutionStatus.RESOLVED;
        if ((resolved && time == null) || (!resolved && time != null)) {
            throw new IllegalArgumentException("inconsistent reservation time result");
        }
    }

    public static ReservationTimeResolutionResult resolved(
            long storeId,
            ResolvedReservationTime time
    ) {
        return new ReservationTimeResolutionResult(
                storeId,
                ReservationTimeResolutionStatus.RESOLVED,
                time
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
