package com.miriyum.domain.search.model;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 공개 매장 검색에서 예약 가용성을 요청하는 완전한 조건이다.
 *
 * @param serviceDate 예약 서비스 날짜
 * @param startTime 예약 시작 시각
 * @param partySize 예약 인원 수(1~100명)
 */
public record ReservationSearchCondition(
        LocalDate serviceDate,
        LocalTime startTime,
        int partySize
) {

    /**
     * 필수 날짜·시각과 허용 인원 범위를 만족하는 예약 검색 조건을 만든다.
     *
     * @throws ServiceException 필수값이 없거나 인원이 1~100명 범위를 벗어난 경우
     */
    public ReservationSearchCondition {
        if (serviceDate == null
                || startTime == null
                || startTime.getSecond() != 0
                || startTime.getNano() != 0
                || partySize < 1
                || partySize > 100) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * 선택 입력 세 개가 모두 없으면 조건 없음으로, 모두 있으면 완전한 예약 조건으로 변환한다.
     *
     * @throws ServiceException 일부 값만 제공되거나 값의 범위가 유효하지 않은 경우
     */
    public static ReservationSearchCondition fromNullable(
            LocalDate serviceDate,
            LocalTime startTime,
            Integer partySize
    ) {
        boolean anyValue = serviceDate != null || startTime != null || partySize != null;
        boolean allValues = serviceDate != null && startTime != null && partySize != null;
        if (anyValue != allValues) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        return allValues
                ? new ReservationSearchCondition(serviceDate, startTime, partySize)
                : null;
    }
}
