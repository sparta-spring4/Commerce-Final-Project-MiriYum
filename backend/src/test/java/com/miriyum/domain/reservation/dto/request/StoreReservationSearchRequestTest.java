package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

class StoreReservationSearchRequestTest {

    @Test
    @DisplayName("누락된 운영자 목록 조건은 전체 날짜·상태와 기본 페이지·서비스 날짜 오름차순을 사용한다")
    void missingQueryUsesStoreListDefaults() {
        // given & when
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, null, null, null);

        // then
        assertThat(request.serviceDate()).isNull();
        assertThat(request.status()).isNull();
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.order())
                .isEqualTo(StoreReservationSearchRequest.Order.SERVICE_DATE_ASC);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "CONFIRMED, CONFIRMED",
        "CANCELLED, CANCELLED",
        "FULFILLED, FULFILLED"
    })
    @DisplayName("1차 MVP 예약 상태만 운영자 목록 조건으로 허용한다")
    void acceptsApprovedReservationStatuses(
            String rawStatus,
            StoreReservationSearchRequest.Status expected
    ) {
        // given & when
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, rawStatus, 0, 20, null);

        // then
        assertThat(request.status()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "'serviceDate,asc', SERVICE_DATE_ASC",
        "'serviceDate,desc', SERVICE_DATE_DESC",
        "'createdAt,asc', CREATED_AT_ASC",
        "'createdAt,desc', CREATED_AT_DESC"
    })
    @DisplayName("OpenAPI가 허용한 네 가지 운영자 목록 정렬만 허용한다")
    void acceptsApprovedSorts(
            String rawSort,
            StoreReservationSearchRequest.Order expected
    ) {
        // given & when
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, 0, 20, rawSort);

        // then
        assertThat(request.order()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("approvedSortOrders")
    @DisplayName("모든 운영자 목록 정렬은 같은 방향의 예약 ID 보조 정렬을 제공한다")
    void providesSameDirectionReservationIdTieBreaker(
            String rawSort,
            String primaryProperty,
            Sort.Direction direction
    ) {
        // given & when
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, 0, 20, rawSort);

        // then
        assertThat(request.order().sortOrders())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple(primaryProperty, direction),
                        tuple("id", direction)
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "NO_SHOW", ""})
    @DisplayName("허용하지 않은 예약 상태는 COMMON_001로 거절한다")
    void invalidStatusIsRejected(String status) {
        // given & when & then
        assertValidationFailed(() ->
                StoreReservationSearchRequest.from(null, status, 0, 20, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "serviceDate",
        "serviceDate,DESC",
        "status,asc",
        "serviceDate,desc,id,desc",
        ""
    })
    @DisplayName("허용 목록 밖의 정렬은 COMMON_001로 거절한다")
    void invalidSortIsRejected(String sort) {
        // given & when & then
        assertValidationFailed(() ->
                StoreReservationSearchRequest.from(null, null, 0, 20, sort));
    }

    @ParameterizedTest
    @MethodSource("invalidPageArguments")
    @DisplayName("음수 페이지와 1~100 범위를 벗어난 크기는 COMMON_001로 거절한다")
    void invalidPageBoundaryIsRejected(Integer page, Integer size) {
        // given & when & then
        assertValidationFailed(() ->
                StoreReservationSearchRequest.from(null, null, page, size, null));
    }

    private static Stream<Arguments> invalidPageArguments() {
        return Stream.of(
                Arguments.of(-1, 20),
                Arguments.of(0, 0),
                Arguments.of(0, 101)
        );
    }

    private static Stream<Arguments> approvedSortOrders() {
        return Stream.of(
                Arguments.of("serviceDate,asc", "serviceDate", Sort.Direction.ASC),
                Arguments.of("serviceDate,desc", "serviceDate", Sort.Direction.DESC),
                Arguments.of("createdAt,asc", "createdAt", Sort.Direction.ASC),
                Arguments.of("createdAt,desc", "createdAt", Sort.Direction.DESC)
        );
    }

    private void assertValidationFailed(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
