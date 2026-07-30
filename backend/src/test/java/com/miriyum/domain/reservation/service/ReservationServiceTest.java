package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(reservationRepository);
    }

    @Test
    @DisplayName("상태 필터가 없으면 계정 범위 전체 조회를 사용한다")
    void findsAllConsumerHistoryWithoutStatusFilter() {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, 0, 20, null);
        given(reservationRepository.findAllByConsumerAccountId(
                eq(11L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        ReservationHistoryPageResponse response =
                reservationService.getConsumerReservationHistory(11L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByConsumerAccountId(eq(11L), pageable.capture());
        then(reservationRepository).should(never())
                .findAllByConsumerAccountIdAndStatus(
                        anyLong(),
                        any(ReservationStatus.class),
                        any(Pageable.class)
                );
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("상태 필터가 있으면 계정과 상태를 함께 제한한다")
    void filtersConsumerHistoryByStatus() {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(
                        "CANCELLED",
                        2,
                        10,
                        "serviceDate,asc"
                );
        given(reservationRepository.findAllByConsumerAccountIdAndStatus(
                eq(11L),
                eq(ReservationStatus.CANCELLED),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(2, 10)));

        // when
        reservationService.getConsumerReservationHistory(11L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByConsumerAccountIdAndStatus(
                        eq(11L),
                        eq(ReservationStatus.CANCELLED),
                        pageable.capture()
                );
        then(reservationRepository).should(never())
                .findAllByConsumerAccountId(anyLong(), any(Pageable.class));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @ParameterizedTest(name = "{0} 정렬")
    @MethodSource("approvedSortCases")
    @DisplayName("승인된 정렬은 같은 방향의 예약 ID 보조 정렬을 사용한다")
    void addsReservationIdTieBreaker(
            String externalSort,
            String primaryProperty,
            Sort.Direction direction
    ) {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, 0, 20, externalSort);
        given(reservationRepository.findAllByConsumerAccountId(
                eq(11L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        reservationService.getConsumerReservationHistory(11L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByConsumerAccountId(eq(11L), pageable.capture());
        assertThat(pageable.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple(primaryProperty, direction),
                        tuple("id", direction)
                );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    @DisplayName("유효하지 않은 소비자 계정 ID는 COMMON_001로 거절한다")
    void rejectsInvalidConsumerAccountId(Long consumerAccountId) {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, null, null, null);

        // when & then
        assertValidationFailure(() ->
                reservationService.getConsumerReservationHistory(
                        consumerAccountId,
                        request
                ));
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("조회 조건이 없으면 COMMON_001로 거절한다")
    void rejectsNullRequest() {
        // when & then
        assertValidationFailure(() ->
                reservationService.getConsumerReservationHistory(11L, null));
        then(reservationRepository).shouldHaveNoInteractions();
    }

    private static Stream<Arguments> approvedSortCases() {
        return Stream.of(
                Arguments.of("createdAt,desc", "createdAt", Sort.Direction.DESC),
                Arguments.of("createdAt,asc", "createdAt", Sort.Direction.ASC),
                Arguments.of("serviceDate,desc", "serviceDate", Sort.Direction.DESC),
                Arguments.of("serviceDate,asc", "serviceDate", Sort.Direction.ASC)
        );
    }

    private void assertValidationFailure(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
