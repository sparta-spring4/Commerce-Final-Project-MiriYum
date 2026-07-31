package com.miriyum.domain.reservation.dto.request;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.Sort;

/**
 * 매장 운영자가 관리 권한이 있는 매장의 예약 목록을 조회하는 조건이다.
 *
 * @param serviceDate 선택한 예약 서비스 날짜, 전체 조회면 {@code null}
 * @param status 선택한 1차 MVP 예약 상태, 전체 조회면 {@code null}
 * @param page 0부터 시작하는 페이지 번호
 * @param size 페이지 크기
 * @param order OpenAPI가 허용한 단일 정렬
 */
public record StoreReservationSearchRequest(
        LocalDate serviceDate,
        Status status,
        int page,
        int size,
        Order order
) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    public StoreReservationSearchRequest {
        if (page < DEFAULT_PAGE || size < 1 || size > MAX_SIZE || order == null) {
            throw invalidRequest();
        }
    }

    /**
     * 공개 query 값을 예약 도메인이 검증한 운영자 목록 조회 조건으로 변환한다.
     *
     * @param serviceDate 선택한 예약 서비스 날짜
     * @param status 공개 예약 상태 문자열
     * @param page 0부터 시작하는 페이지 번호
     * @param size 1~100 범위의 페이지 크기
     * @param sort 공개 정렬 문자열
     * @return 검증과 기본값 적용을 마친 조회 조건
     * @throws ServiceException 허용하지 않은 상태·페이지·크기·정렬인 경우
     */
    public static StoreReservationSearchRequest from(
            LocalDate serviceDate,
            String status,
            Integer page,
            Integer size,
            String sort
    ) {
        int normalizedPage = page == null ? DEFAULT_PAGE : page;
        int normalizedSize = size == null ? DEFAULT_SIZE : size;
        return new StoreReservationSearchRequest(
                serviceDate,
                Status.fromNullable(status),
                normalizedPage,
                normalizedSize,
                Order.fromNullable(sort)
        );
    }

    /**
     * 운영자 목록 조회에 공개하는 1차 MVP 예약 상태다.
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
     * 운영자 예약 목록 OpenAPI가 허용한 단일 정렬이다.
     */
    public enum Order {
        SERVICE_DATE_ASC("serviceDate,asc", "serviceDate", Sort.Direction.ASC),
        SERVICE_DATE_DESC("serviceDate,desc", "serviceDate", Sort.Direction.DESC),
        CREATED_AT_ASC("createdAt,asc", "createdAt", Sort.Direction.ASC),
        CREATED_AT_DESC("createdAt,desc", "createdAt", Sort.Direction.DESC);

        private final String externalValue;
        private final String primaryProperty;
        private final Sort.Direction direction;

        Order(
                String externalValue,
                String primaryProperty,
                Sort.Direction direction
        ) {
            this.externalValue = externalValue;
            this.primaryProperty = primaryProperty;
            this.direction = direction;
        }

        /**
         * 페이지 경계를 결정적으로 유지하는 주 정렬과 예약 ID 보조 정렬을 제공한다.
         *
         * @return 주 속성과 같은 방향의 예약 ID 보조 정렬
         */
        public List<Sort.Order> sortOrders() {
            return List.of(
                    new Sort.Order(direction, primaryProperty),
                    new Sort.Order(direction, "id")
            );
        }

        private static Order fromNullable(String value) {
            if (value == null) {
                return SERVICE_DATE_ASC;
            }
            return Arrays.stream(values())
                    .filter(order -> order.externalValue.equals(value))
                    .findFirst()
                    .orElseThrow(StoreReservationSearchRequest::invalidRequest);
        }
    }

    private static ServiceException invalidRequest() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
