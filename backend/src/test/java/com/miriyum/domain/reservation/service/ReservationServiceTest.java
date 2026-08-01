package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.request.StoreReservationSearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationPageResponse;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
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

    @Mock
    private ConsumerAccountService consumerAccountService;

    @Mock
    private StoreService storeService;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                reservationRepository,
                consumerAccountService,
                storeService
        );
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
        then(consumerAccountService).should().getMe(11L);
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
        then(consumerAccountService).should().getMe(11L);
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

    @Test
    @DisplayName("정지된 소비자 계정은 예약 내역을 조회하지 않는다")
    void rejectsRestrictedConsumerBeforeQuery() {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, null, null, null);
        given(consumerAccountService.getMe(11L))
                .willThrow(new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED));

        // when & then
        assertThatThrownBy(() ->
                reservationService.getConsumerReservationHistory(11L, request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("운영자 목록은 매장 관리 권한을 확인한 뒤 대상 매장만 조회한다")
    void findsStoreReservationsAfterManagementAuthorization() {
        // given
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, 0, 20, null);
        given(reservationRepository.findAllByStoreId(
                eq(22L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        StoreReservationPageResponse response =
                reservationService.getStoreReservations(33L, 22L, request);

        // then
        then(storeService).should().requireManagementOwnership(33L, 22L);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByStoreId(eq(22L), pageable.capture());
        assertThat(pageable.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("serviceDate", Sort.Direction.ASC),
                        tuple("id", Sort.Direction.ASC)
                );
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("운영자 목록은 서비스 날짜와 상태를 함께 제한한다")
    void filtersStoreReservationsByServiceDateAndStatus() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 1);
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(
                        serviceDate,
                        "CONFIRMED",
                        1,
                        10,
                        "createdAt,desc"
                );
        given(reservationRepository.findAllByStoreIdAndServiceDateAndStatus(
                eq(22L),
                eq(serviceDate),
                eq(ReservationStatus.CONFIRMED),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(1, 10)));

        // when
        reservationService.getStoreReservations(33L, 22L, request);

        // then
        then(storeService).should().requireManagementOwnership(33L, 22L);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByStoreIdAndServiceDateAndStatus(
                        eq(22L),
                        eq(serviceDate),
                        eq(ReservationStatus.CONFIRMED),
                        pageable.capture()
                );
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("매장 관리 권한이 없으면 예약 목록을 조회하지 않는다")
    void rejectsStoreAccessBeforeQuery() {
        // given
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, null, null, null);
        willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED))
                .given(storeService)
                .requireManagementOwnership(33L, 22L);

        // when & then
        assertThatThrownBy(() ->
                reservationService.getStoreReservations(33L, 22L, request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.ACCESS_DENIED));
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("운영자·매장 ID 또는 조회 조건이 유효하지 않으면 조회하지 않는다")
    void rejectsInvalidStoreQueryScope() {
        // given
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, null, null, null);

        // when & then
        assertValidationFailure(() ->
                reservationService.getStoreReservations(0L, 22L, request));
        assertValidationFailure(() ->
                reservationService.getStoreReservations(33L, 0L, request));
        assertValidationFailure(() ->
                reservationService.getStoreReservations(33L, 22L, null));
        then(storeService).shouldHaveNoInteractions();
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
