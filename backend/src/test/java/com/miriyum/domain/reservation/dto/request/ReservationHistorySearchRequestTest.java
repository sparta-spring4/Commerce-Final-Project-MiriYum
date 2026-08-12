package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReservationHistorySearchRequestTest {

    @Test
    @DisplayName("누락된 조회 조건은 전체 상태·0페이지·20개·생성 역순으로 정규화한다")
    void missingQueryUsesApprovedDefaults() {
        // given & when
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, null, null, null);

        // then
        assertThat(request.status()).isNull();
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.order())
                .isEqualTo(ReservationHistorySearchRequest.Order.CREATED_AT_DESC);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "CONFIRMED, CONFIRMED",
        "CANCELLED, CANCELLED",
        "FULFILLED, FULFILLED"
    })
    @DisplayName("1차 MVP 예약 상태만 조회 조건으로 허용한다")
    void acceptsApprovedReservationStatuses(
            String rawStatus,
            ReservationHistorySearchRequest.Status expected
    ) {
        // given & when
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(rawStatus, 0, 20, null);

        // then
        assertThat(request.status()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "'createdAt,desc', CREATED_AT_DESC",
        "'createdAt,asc', CREATED_AT_ASC",
        "'serviceDate,desc', SERVICE_DATE_DESC",
        "'serviceDate,asc', SERVICE_DATE_ASC",
        "'startAt,desc', START_AT_DESC",
        "'startAt,asc', START_AT_ASC"
    })
    @DisplayName("OpenAPI가 승인한 네 가지 예약 내역 정렬만 허용한다")
    void acceptsApprovedSorts(
            String rawSort,
            ReservationHistorySearchRequest.Order expected
    ) {
        // given & when
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, 0, 20, rawSort);

        // then
        assertThat(request.order()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "NO_SHOW", ""})
    @DisplayName("1차 MVP 예약 상태가 아니면 COMMON_001로 거절한다")
    void invalidStatusIsRejected(String status) {
        assertValidationFailed(() ->
                ReservationHistorySearchRequest.from(status, 0, 20, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "createdAt",
        "createdAt,DESC",
        "status,asc",
        "serviceDate,desc,id,desc",
        ""
    })
    @DisplayName("승인 목록 밖의 정렬은 COMMON_001로 거절한다")
    void invalidSortIsRejected(String sort) {
        assertValidationFailed(() ->
                ReservationHistorySearchRequest.from(null, 0, 20, sort));
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    @DisplayName("페이지와 크기 경계를 벗어나면 COMMON_001로 거절한다")
    void invalidPageBoundaryIsRejected(Integer page, Integer size) {
        assertValidationFailed(() ->
                ReservationHistorySearchRequest.from(null, page, size, null));
    }

    private static Stream<Arguments> invalidPages() {
        return Stream.of(
                Arguments.of(-1, 20),
                Arguments.of(0, 0),
                Arguments.of(0, -1),
                Arguments.of(0, 101));
    }

    private void assertValidationFailed(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
