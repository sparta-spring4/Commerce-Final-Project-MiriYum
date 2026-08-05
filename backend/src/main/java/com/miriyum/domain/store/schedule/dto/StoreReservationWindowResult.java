package com.miriyum.domain.store.schedule.dto;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 매장 현지 시각으로 해석한 활성 예약 접수 구간 결과다.
 *
 * <p>{@link StoreReservationWindowStatus#ACCEPTING} 결과만 시간대와 구간을 가진다.
 * 구간 종료는 예약 접수 가능 구간의 상한이며 실제 예약 점유 종료 시각이 아니다.</p>
 *
 * @param storeId 대상 매장 ID
 * @param status 활성 예약 접수 구간 존재 여부
 * @param timeZoneId 매장의 IANA 시간대 또는 접수하지 않으면 {@code null}
 * @param windowStartAt 매장 현지 구간 시작 또는 접수하지 않으면 {@code null}
 * @param windowEndAt 매장 현지 구간 종료 또는 접수하지 않으면 {@code null}
 */
public record StoreReservationWindowResult(
        long storeId,
        StoreReservationWindowStatus status,
        String timeZoneId,
        LocalDateTime windowStartAt,
        LocalDateTime windowEndAt
) {

    public StoreReservationWindowResult {
        if (storeId <= 0 || status == null) {
            throw new IllegalArgumentException("invalid reservation window result");
        }
        boolean accepting = status == StoreReservationWindowStatus.ACCEPTING;
        boolean completeWindow = timeZoneId != null
                && windowStartAt != null
                && windowEndAt != null
                && windowStartAt.isBefore(windowEndAt);
        boolean emptyWindow = timeZoneId == null
                && windowStartAt == null
                && windowEndAt == null;
        if ((accepting && !completeWindow) || (!accepting && !emptyWindow)) {
            throw new IllegalArgumentException(
                    "inconsistent reservation window result");
        }
        if (accepting) {
            try {
                ZoneId.of(timeZoneId);
            } catch (DateTimeException exception) {
                throw new IllegalArgumentException(
                        "invalid reservation window time zone",
                        exception);
            }
        }
    }

    /**
     * 활성 예약 접수 구간 결과를 만든다.
     */
    public static StoreReservationWindowResult accepting(
            long storeId,
            String timeZoneId,
            LocalDateTime windowStartAt,
            LocalDateTime windowEndAt
    ) {
        return new StoreReservationWindowResult(
                storeId,
                StoreReservationWindowStatus.ACCEPTING,
                timeZoneId,
                windowStartAt,
                windowEndAt);
    }

    /**
     * 활성 예약 접수 구간이 없는 실패 폐쇄 결과를 만든다.
     */
    public static StoreReservationWindowResult notAccepting(long storeId) {
        return new StoreReservationWindowResult(
                storeId,
                StoreReservationWindowStatus.NOT_ACCEPTING,
                null,
                null,
                null);
    }
}
