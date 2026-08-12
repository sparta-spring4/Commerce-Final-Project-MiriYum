package com.miriyum.domain.reservation.dto.request;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.Arrays;

/**
 * 일반 사용자 예약 내역의 상태·페이지·정렬 조회 조건이다.
 *
 * @param status 선택한 1차 MVP 예약 상태, 전체 조회이면 {@code null}
 * @param page 0부터 시작하는 페이지 번호
 * @param size 페이지 크기
 * @param order 승인된 단일 정렬
 */
public record ReservationHistorySearchRequest(
        Status status,
        int page,
        int size,
        Order order
) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    public ReservationHistorySearchRequest {
        if (page < DEFAULT_PAGE || size < 1 || size > MAX_SIZE || order == null) {
            throw invalidRequest();
        }
    }

    /**
     * 공개 query 값을 예약 도메인이 승인한 조회 조건으로 변환한다.
     *
     * @param status 공개 예약 상태 문자열
     * @param page 0부터 시작하는 페이지 번호
     * @param size 1~100 범위의 페이지 크기
     * @param sort 공개 정렬 문자열
     * @return 검증과 기본값 적용이 끝난 조회 조건
     * @throws ServiceException 승인되지 않은 상태·페이지·크기·정렬인 경우
     */
    public static ReservationHistorySearchRequest from(
            String status,
            Integer page,
            Integer size,
            String sort
    ) {
        int normalizedPage = page == null ? DEFAULT_PAGE : page;
        int normalizedSize = size == null ? DEFAULT_SIZE : size;
        return new ReservationHistorySearchRequest(
                Status.fromNullable(status),
                normalizedPage,
                normalizedSize,
                Order.fromNullable(sort)
        );
    }

    /**
     * 마이페이지에 공개하는 1차 MVP 예약 상태다.
     */
    public enum Status {
        CONFIRMED,
        CANCELLED,
        FULFILLED;

        private static Status fromNullable(String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw invalidRequest();
            }
        }
    }

    /**
     * 마이페이지 OpenAPI가 허용한 예약 내역 정렬이다.
     */
    public enum Order {
        CREATED_AT_DESC("createdAt,desc"),
        CREATED_AT_ASC("createdAt,asc"),
        SERVICE_DATE_DESC("serviceDate,desc"),
        SERVICE_DATE_ASC("serviceDate,asc"),
        START_AT_DESC("startAt,desc"),
        START_AT_ASC("startAt,asc");

        private final String externalValue;

        Order(String externalValue) {
            this.externalValue = externalValue;
        }

        private static Order fromNullable(String value) {
            if (value == null) {
                return CREATED_AT_DESC;
            }
            return Arrays.stream(values())
                    .filter(order -> order.externalValue.equals(value))
                    .findFirst()
                    .orElseThrow(ReservationHistorySearchRequest::invalidRequest);
        }
    }

    private static ServiceException invalidRequest() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
