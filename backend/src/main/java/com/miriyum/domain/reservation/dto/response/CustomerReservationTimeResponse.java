package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 고객에게 공개하는 예약 서비스 시간이다.
 *
 * <p>전환 시간을 포함한 내부 {@code occupancyEndAt}은 공개하지 않는다.</p>
 *
 * @param serviceDate 매장 현지 시작 날짜
 * @param timeStatus 실제 Instant 스냅샷 해석 가능 상태
 * @param startAt 계산 당시 offset을 포함한 서비스 시작
 * @param serviceEndAt 계산 당시 offset을 포함한 고객 서비스 종료
 * @param timeZoneId 계산에 사용한 매장 IANA 시간대
 */
public record CustomerReservationTimeResponse(
        LocalDate serviceDate,
        CustomerReservationTimeStatus timeStatus,
        OffsetDateTime startAt,
        OffsetDateTime serviceEndAt,
        String timeZoneId
) {

    public CustomerReservationTimeResponse {
        if (serviceDate == null || timeStatus == null) {
            throw new IllegalArgumentException("serviceDate and timeStatus are required");
        }
        boolean completeResolvedTime = startAt != null
                && serviceEndAt != null
                && timeZoneId != null
                && !timeZoneId.isBlank();
        if ((timeStatus == CustomerReservationTimeStatus.RESOLVED
                && !completeResolvedTime)
                || (timeStatus == CustomerReservationTimeStatus.LEGACY_UNRESOLVED
                && (startAt != null || serviceEndAt != null || timeZoneId != null))) {
            throw new IllegalArgumentException("inconsistent customer reservation time");
        }
    }

    /**
     * 저장된 거래 스냅샷에서 고객 공개 필드만 선택한다.
     *
     * @param snapshot 예약 당시 시간 스냅샷
     * @return 고객 공개 서비스 시간
     */
    public static CustomerReservationTimeResponse from(ReservationTimeSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("time snapshot is required");
        }
        if (!snapshot.hasResolvedTime()) {
            return new CustomerReservationTimeResponse(
                    snapshot.getServiceDate(),
                    CustomerReservationTimeStatus.LEGACY_UNRESOLVED,
                    null,
                    null,
                    null
            );
        }
        return new CustomerReservationTimeResponse(
                snapshot.getServiceDate(),
                CustomerReservationTimeStatus.RESOLVED,
                OffsetDateTime.ofInstant(
                        snapshot.getStartAt(),
                        ZoneOffset.ofTotalSeconds(snapshot.getStartOffsetSeconds())
                ),
                OffsetDateTime.ofInstant(
                        snapshot.getServiceEndAt(),
                        ZoneOffset.ofTotalSeconds(snapshot.getServiceEndOffsetSeconds())
                ),
                snapshot.getTimeZoneId()
        );
    }
}
