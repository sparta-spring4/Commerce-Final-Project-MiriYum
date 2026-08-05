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
import static org.mockito.Mockito.times;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.request.StoreReservationSearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.dto.response.StoreReservationPageResponse;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowResult;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 3);
    private static final LocalDate CAPACITY_SERVICE_DATE = LocalDate.of(2026, 8, 2);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);
    private static final LocalTime CAPACITY_END_TIME = LocalTime.of(19, 0);

    @Mock
    private StoreScheduleService storeScheduleService;

    @Mock
    private StoreServiceIntervalValidationService storeServiceIntervalValidationService;

    @Mock
    private ReservationTimePolicyVersionRepository timePolicyRepository;

    @Mock
    private StoreService storeService;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    @Mock
    private ReservationTimePolicyAuditRepository timePolicyAuditRepository;

    @Mock
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ConsumerAccountService consumerAccountService;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                storeScheduleService,
                storeServiceIntervalValidationService,
                timePolicyRepository,
                storeService,
                idempotencyExecutor,
                timePolicyAuditRepository,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                capacityBucketRepository,
                reservationRepository,
                consumerAccountService
        );
    }

    @Test
    @DisplayName("입력 순서와 중복을 보존하며 accepting 매장별 정책으로 서로 다른 점유 종료를 계산한다")
    void resolvesPerStoreTimesInInputOrderWithOnePolicyQuery() {
        // given
        List<Long> storeIds = List.of(2L, 1L, 3L, 2L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(
                StoreReservationWindowResult.accepting(
                        2L,
                        "Asia/Seoul",
                        requestedAt.minusHours(1),
                        requestedAt.plusMinutes(30)
                ),
                StoreReservationWindowResult.accepting(
                        1L,
                        "Asia/Seoul",
                        requestedAt,
                        requestedAt.plusMinutes(30)
                ),
                StoreReservationWindowResult.notAccepting(3L),
                StoreReservationWindowResult.accepting(
                        2L,
                        "Asia/Seoul",
                        requestedAt.minusHours(1),
                        requestedAt.plusMinutes(30)
                )
        ));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(
                activePolicy(1L, 30, 90, 30),
                activePolicy(2L, 30, 60, 15)
        ));
        StoreServiceIntervalRequest store2Interval = new StoreServiceIntervalRequest(
                2L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")
        );
        StoreServiceIntervalRequest store1Interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:30:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(store2Interval, store1Interval, store2Interval)
        )).willReturn(List.of(
                StoreServiceIntervalResult.of(store2Interval, true),
                StoreServiceIntervalResult.of(store1Interval, false),
                StoreServiceIntervalResult.of(store2Interval, true)
        ));

        // when
        List<ReservationTimeResolutionResult> results = reservationService
                .resolveReservationTimes(
                        storeIds,
                        new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
                );

        // then
        assertThat(results).extracting(ReservationTimeResolutionResult::storeId)
                .containsExactly(2L, 1L, 3L, 2L);
        assertThat(results).extracting(ReservationTimeResolutionResult::status)
                .containsExactly(
                        ReservationTimeResolutionStatus.RESOLVED,
                        ReservationTimeResolutionStatus.UNAVAILABLE,
                        ReservationTimeResolutionStatus.UNAVAILABLE,
                        ReservationTimeResolutionStatus.RESOLVED
                );
        assertThat(results.get(0).time().occupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-03T10:15:00Z"));
        assertThat(results.get(1).time()).isNull();
        assertThat(results.get(2).time()).isNull();
        assertThat(results.get(3).time().occupancyEndAt())
                .isEqualTo(results.get(0).time().occupancyEndAt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        then(timePolicyRepository).should(times(1)).findResolutionCandidatesByStoreIds(
                idsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        );
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StoreServiceIntervalRequest>> intervalCaptor =
                ArgumentCaptor.forClass(List.class);
        then(storeServiceIntervalValidationService).should(times(1))
                .validateServiceIntervals(intervalCaptor.capture());
        assertThat(intervalCaptor.getValue()).containsExactly(
                store2Interval,
                store1Interval,
                store2Interval
        );
        assertThat(intervalCaptor.getValue().getFirst().serviceEndAt())
                .isNotEqualTo(results.getFirst().time().occupancyEndAt());
    }

    @Test
    @DisplayName("모든 매장이 시작 시각을 접수하지 않으면 시간 정책 repository를 조회하지 않는다")
    void skipsPolicyRepositoryWhenAllStoresAreNotAccepting() {
        List<Long> storeIds = List.of(3L, 3L, 4L);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(
                StoreReservationWindowResult.notAccepting(3L),
                StoreReservationWindowResult.notAccepting(3L),
                StoreReservationWindowResult.notAccepting(4L)
        ));

        List<ReservationTimeResolutionResult> results = reservationService
                .resolveReservationTimes(
                        storeIds,
                        new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
                );

        assertThat(results).extracting(ReservationTimeResolutionResult::storeId)
                .containsExactly(3L, 3L, 4L);
        assertThat(results).extracting(ReservationTimeResolutionResult::status)
                .containsOnly(ReservationTimeResolutionStatus.UNAVAILABLE);
        then(timePolicyRepository).should(never()).findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        then(storeServiceIntervalValidationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("슬롯 정렬은 자정이 아니라 Store가 반환한 windowStartAt을 기준으로 판정한다")
    void rejectsStartMisalignedFromWindowStart() {
        List<Long> storeIds = List.of(1L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                1L,
                "Asia/Seoul",
                requestedAt.minusMinutes(50),
                requestedAt.plusHours(2)
        )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 0)));
        List<ReservationTimeResolutionResult> results = reservationService
                .resolveReservationTimes(
                        storeIds,
                        new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
                );

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
            assertThat(result.time()).isNull();
        });
        then(storeServiceIntervalValidationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("DST 중복 시각은 offset 없이는 실패 폐쇄하고 유효 offset이 있으면 계산한다")
    void requiresExplicitOffsetForAmbiguousDstStart() {
        LocalDate serviceDate = LocalDate.of(2026, 10, 25);
        LocalTime startTime = LocalTime.of(2, 30);
        LocalDateTime requestedAt = LocalDateTime.of(serviceDate, startTime);
        List<Long> storeIds = List.of(1L);
        given(storeScheduleService.resolveReservationWindows(storeIds, serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        1L,
                        "Europe/Paris",
                        requestedAt.minusMinutes(30),
                        requestedAt.plusMinutes(30)
                )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 0)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-10-25T00:30:00Z"),
                Instant.parse("2026-10-25T01:30:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(interval)
        )).willReturn(List.of(StoreServiceIntervalResult.of(interval, true)));

        ReservationTimeResolutionResult ambiguous = reservationService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(serviceDate, startTime, null)
        ).getFirst();
        ReservationTimeResolutionResult explicit = reservationService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(serviceDate, startTime, ZoneOffset.ofHours(2))
        ).getFirst();

        assertThat(ambiguous.status()).isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
        assertThat(explicit.status()).isEqualTo(ReservationTimeResolutionStatus.RESOLVED);
        assertThat(explicit.time().startAt())
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        then(timePolicyRepository).should(times(2)).findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        );
        then(storeServiceIntervalValidationService).should(times(1))
                .validateServiceIntervals(List.of(interval));
    }

    @Test
    @DisplayName("정책 원본이 없으면 해당 매장만 실패 폐쇄한다")
    void failsClosedWhenTimePolicyIsMissing() {
        List<Long> storeIds = List.of(1L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                1L,
                "Asia/Seoul",
                requestedAt,
                requestedAt.plusHours(2)
        )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of());

        assertThat(reservationService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).singleElement().satisfies(result ->
                assertThat(result.status())
                        .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE));
        then(storeServiceIntervalValidationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Store 서비스 구간 응답이 계약과 다르면 입력 전체를 실패 폐쇄한다")
    void failsClosedWhenStoreServiceIntervalResponseIsMalformed() {
        List<Long> storeIds = List.of(1L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                1L,
                "Asia/Seoul",
                requestedAt,
                requestedAt.plusHours(2)
        )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 15)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(interval)
        )).willReturn(
                null,
                List.of(),
                List.of(new StoreServiceIntervalResult(
                        2L,
                        interval.startAt(),
                        interval.serviceEndAt(),
                        com.miriyum.domain.store.schedule.dto.StoreServiceIntervalStatus.ACCEPTING
                )),
                List.of(new StoreServiceIntervalResult(
                        interval.storeId(),
                        interval.startAt(),
                        interval.serviceEndAt(),
                        null
                ))
        );

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(reservationService.resolveReservationTimes(
                    storeIds,
                    new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
            )).singleElement().satisfies(result -> {
                assertThat(result.status())
                        .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
                assertThat(result.time()).isNull();
            });
        }
        then(storeServiceIntervalValidationService).should(times(4))
                .validateServiceIntervals(List.of(interval));
    }

    @Test
    @DisplayName("효력 시각이 지났지만 아직 전환되지 않은 게시 예약이 있으면 기존 ACTIVE를 사용하지 않는다")
    void failsClosedWhileDueScheduledPolicyIsPendingActivation() {
        List<Long> storeIds = List.of(1L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                1L,
                "Asia/Seoul",
                requestedAt,
                requestedAt.plusHours(2)
        )));
        ReservationTimePolicyVersion due = ReservationTimePolicyVersion.createDraft(
                1L,
                2L,
                30,
                120,
                0
        );
        due.schedule(NOW, NOW.minusSeconds(60), "효력 도달 정책");
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(
                activePolicy(1L, 30, 60, 0),
                due
        ));

        assertThat(reservationService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).singleElement().satisfies(result -> {
            assertThat(result.status())
                    .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
            assertThat(result.time()).isNull();
        });
    }

    @Test
    @DisplayName("빈 매장 입력은 Store와 정책 repository를 조회하지 않고 빈 결과를 반환한다")
    void returnsEmptyWithoutDependenciesForEmptyStoreInput() {
        assertThat(reservationService.resolveReservationTimes(
                List.of(),
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).isEmpty();

        then(storeScheduleService).shouldHaveNoInteractions();
        then(timePolicyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("매장별로 계산한 서로 다른 점유 종료 시각으로 수용량을 한 번에 판정한다")
    void getAvailabilitiesUsesPerStoreResolvedOccupancyEnds() {
        List<Long> storeIds = List.of(1L, 2L);
        LocalDateTime requestedAt = LocalDateTime.of(CAPACITY_SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                CAPACITY_SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(
                StoreReservationWindowResult.accepting(
                        1L,
                        "Asia/Seoul",
                        requestedAt,
                        requestedAt.plusHours(2)
                ),
                StoreReservationWindowResult.accepting(
                        2L,
                        "Asia/Seoul",
                        requestedAt,
                        requestedAt.plusHours(2)
                )
        ));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(
                activePolicy(1L, 30, 60, 0),
                activePolicy(2L, 30, 90, 0)
        ));
        StoreServiceIntervalRequest store1Interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-08-02T09:00:00Z"),
                Instant.parse("2026-08-02T10:00:00Z")
        );
        StoreServiceIntervalRequest store2Interval = new StoreServiceIntervalRequest(
                2L,
                Instant.parse("2026-08-02T09:00:00Z"),
                Instant.parse("2026-08-02T10:30:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(store1Interval, store2Interval)
        )).willReturn(List.of(
                StoreServiceIntervalResult.of(store1Interval, true),
                StoreServiceIntervalResult.of(store2Interval, true)
        ));
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                storeIds,
                CAPACITY_SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 30)
        )).willReturn(List.of(
                capacityBucket(1L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(1L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0),
                capacityBucket(1L, LocalTime.of(19, 0), LocalTime.of(19, 30), 0, 0),
                capacityBucket(2L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(2L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0),
                capacityBucket(2L, LocalTime.of(19, 0), LocalTime.of(19, 30), 0, 0)
        ));

        List<ReservationAvailabilityResult> results = reservationService.getAvailabilities(
                storeIds,
                new ReservationAvailabilityCondition(
                        CAPACITY_SERVICE_DATE,
                        START_TIME,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        1L,
                        ReservationAvailabilityStatus.AVAILABLE
                ),
                new ReservationAvailabilityResult(
                        2L,
                        ReservationAvailabilityStatus.AVAILABLE
                )
        );
        then(capacityBucketRepository).should(times(1))
                .findLatestPolicyBucketsOverlapping(
                        storeIds,
                        CAPACITY_SERVICE_DATE,
                        START_TIME,
                        LocalTime.of(19, 30)
                );
    }

    @Test
    @DisplayName("DST 중복 구간은 로컬 수용량 버킷으로 추측하지 않고 실패 폐쇄한다")
    void getAvailabilityFailsClosedForAmbiguousDstCapacityWindow() {
        LocalDate serviceDate = LocalDate.of(2026, 10, 25);
        LocalTime startTime = LocalTime.of(2, 30);
        LocalDateTime requestedAt = LocalDateTime.of(serviceDate, startTime);
        List<Long> storeIds = List.of(1L);
        given(storeScheduleService.resolveReservationWindows(storeIds, serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        1L,
                        "Europe/Paris",
                        requestedAt.minusMinutes(30),
                        requestedAt.plusMinutes(30)
                )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 0)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-10-25T00:30:00Z"),
                Instant.parse("2026-10-25T01:30:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(interval)
        )).willReturn(List.of(StoreServiceIntervalResult.of(interval, true)));

        ReservationAvailabilityResult result = reservationService.getAvailability(
                1L,
                new ReservationAvailabilityCondition(
                        serviceDate,
                        startTime,
                        ZoneOffset.ofHours(2),
                        2,
                        false
                )
        );

        assertThat(result).isEqualTo(new ReservationAvailabilityResult(
                1L,
                ReservationAvailabilityStatus.UNAVAILABLE
        ));
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("자정을 넘는 점유 구간은 로컬 수용량 버킷으로 추측하지 않고 실패 폐쇄한다")
    void getAvailabilityFailsClosedForCrossMidnightCapacityWindow() {
        LocalDate serviceDate = LocalDate.of(2026, 8, 2);
        LocalTime startTime = LocalTime.of(23, 30);
        LocalDateTime requestedAt = LocalDateTime.of(serviceDate, startTime);
        List<Long> storeIds = List.of(1L);
        given(storeScheduleService.resolveReservationWindows(storeIds, serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        1L,
                        "Asia/Seoul",
                        requestedAt.minusMinutes(30),
                        requestedAt.plusMinutes(30)
                )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 90, 0)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-08-02T14:30:00Z"),
                Instant.parse("2026-08-02T16:00:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(interval)
        )).willReturn(List.of(StoreServiceIntervalResult.of(interval, true)));

        ReservationAvailabilityResult result = reservationService.getAvailability(
                1L,
                new ReservationAvailabilityCondition(
                        serviceDate,
                        startTime,
                        null,
                        2,
                        false
                )
        );

        assertThat(result).isEqualTo(new ReservationAvailabilityResult(
                1L,
                ReservationAvailabilityStatus.UNAVAILABLE
        ));
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("요청 구간을 연속으로 덮는 모든 버킷에 여유가 있으면 예약 가능하다")
    void getAvailabilityReturnsAvailableWhenEveryContiguousBucketCanAccept() {
        ReservationAvailabilityCondition condition = capacityCondition(4, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 6, 2),
                capacityBucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 4, 1)
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result).isEqualTo(new ReservationAvailabilityResult(
                22L,
                ReservationAvailabilityStatus.AVAILABLE
        ));
    }

    @Test
    @DisplayName("필요한 구간 사이에 버킷 공백이 있으면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenCoverageHasGap() {
        ReservationAvailabilityCondition condition = capacityCondition(2, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(22L, LocalTime.of(18, 45), LocalTime.of(19, 0), 0, 0)
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("필요 구간에서 버킷이 겹치면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenCoverageOverlaps() {
        ReservationAvailabilityCondition condition = capacityCondition(2, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 45), 0, 0),
                capacityBucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0)
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("필요 구간에 서로 다른 정책 버전이 섞이면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenPolicyVersionsAreMixed() {
        ReservationAvailabilityCondition condition = capacityCondition(2, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(
                        22L,
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        0,
                        0,
                        3L
                ),
                capacityBucket(
                        22L,
                        LocalTime.of(18, 30),
                        LocalTime.of(19, 0),
                        0,
                        0,
                        4L
                )
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("겹치는 버킷 중 하나라도 수용량이 부족하면 전체 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenAnyBucketIsInsufficient() {
        ReservationAvailabilityCondition condition = capacityCondition(4, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 6, 2),
                capacityBucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 8, 1)
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("일괄 판정은 저장소를 한 번 조회하고 입력 매장의 순서와 중복을 보존한다")
    void getAvailabilitiesUsesOneQueryAndPreservesInputOrderAndDuplicates() {
        ReservationAvailabilityCondition condition = capacityCondition(2, false);
        List<Long> storeIds = List.of(30L, 10L, 20L, 30L);
        List<Long> queriedStoreIds = List.of(30L, 10L, 20L);
        givenResolvedCapacityTimes(storeIds, CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                queriedStoreIds,
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(20L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0),
                capacityBucket(30L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(20L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(30L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0)
        ));

        List<ReservationAvailabilityResult> results =
                reservationService.getAvailabilities(storeIds, condition);

        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        30L,
                        ReservationAvailabilityStatus.AVAILABLE
                ),
                new ReservationAvailabilityResult(
                        10L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                ),
                new ReservationAvailabilityResult(
                        20L,
                        ReservationAvailabilityStatus.AVAILABLE
                ),
                new ReservationAvailabilityResult(
                        30L,
                        ReservationAvailabilityStatus.AVAILABLE
                )
        );
        then(capacityBucketRepository).should(times(1))
                .findLatestPolicyBucketsOverlapping(
                        queriedStoreIds,
                        CAPACITY_SERVICE_DATE,
                        START_TIME,
                        CAPACITY_END_TIME
                );
    }

    @Test
    @DisplayName("빈 매장 후보는 저장소를 조회하지 않고 빈 결과를 반환한다")
    void getAvailabilitiesReturnsEmptyWithoutRepositoryQuery() {
        List<ReservationAvailabilityResult> results =
                reservationService.getAvailabilities(
                        List.of(),
                        capacityCondition(2, false)
                );

        assertThat(results).isEmpty();
        then(capacityBucketRepository).should(never())
                .findLatestPolicyBucketsOverlapping(
                        List.of(),
                        CAPACITY_SERVICE_DATE,
                        START_TIME,
                        CAPACITY_END_TIME
                );
    }

    private void givenResolvedCapacityTimes(
            List<Long> storeIds,
            LocalTime occupancyEndTime
    ) {
        LocalDateTime requestedAt =
                LocalDateTime.of(CAPACITY_SERVICE_DATE, START_TIME);
        int durationMinutes = Math.toIntExact(
                Duration.between(START_TIME, occupancyEndTime).toMinutes()
        );
        List<StoreReservationWindowResult> windows = storeIds.stream()
                .map(storeId -> StoreReservationWindowResult.accepting(
                        storeId,
                        "Asia/Seoul",
                        requestedAt,
                        requestedAt.plusHours(2)
                ))
                .toList();
        List<ReservationTimePolicyVersion> policies = storeIds.stream()
                .distinct()
                .map(storeId -> activePolicy(storeId, 30, durationMinutes, 0))
                .toList();
        Instant startAt = requestedAt.toInstant(ZoneOffset.ofHours(9));
        List<StoreServiceIntervalRequest> intervals = storeIds.stream()
                .map(storeId -> new StoreServiceIntervalRequest(
                        storeId,
                        startAt,
                        startAt.plus(Duration.ofMinutes(durationMinutes))
                ))
                .toList();

        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                CAPACITY_SERVICE_DATE,
                START_TIME
        )).willReturn(windows);
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(policies);
        given(storeServiceIntervalValidationService.validateServiceIntervals(intervals))
                .willReturn(intervals.stream()
                        .map(interval -> StoreServiceIntervalResult.of(interval, true))
                        .toList());
    }

    private static ReservationAvailabilityCondition capacityCondition(
            int partySize,
            boolean includesInfants
    ) {
        return new ReservationAvailabilityCondition(
                CAPACITY_SERVICE_DATE,
                START_TIME,
                null,
                partySize,
                includesInfants
        );
    }

    private static ReservationCapacityBucket capacityBucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            int occupiedPeople,
            int occupiedTeams
    ) {
        return capacityBucket(
                storeId,
                startTime,
                endTime,
                occupiedPeople,
                occupiedTeams,
                3L
        );
    }

    private static ReservationCapacityBucket capacityBucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            int occupiedPeople,
            int occupiedTeams,
            long policyVersion
    ) {
        return ReservationCapacityBucket.create(
                storeId,
                CAPACITY_SERVICE_DATE,
                startTime,
                endTime,
                10,
                4,
                occupiedPeople,
                occupiedTeams,
                1,
                6,
                true,
                policyVersion
        );
    }

    private static ReservationTimePolicyVersion activePolicy(
            long storeId,
            int slotIntervalMinutes,
            int serviceDurationMinutes,
            int turnoverDurationMinutes
    ) {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                storeId,
                1L,
                slotIntervalMinutes,
                serviceDurationMinutes,
                turnoverDurationMinutes
        );
        policy.activate(NOW.minusSeconds(1), "활성 정책");
        return policy;
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

    @Test
    @DisplayName("소비자 serviceDate 정렬은 embedded 경로와 같은 방향의 ID 보조 정렬을 사용한다")
    void sortsHistoryByEmbeddedServiceDateThenIdInSameDirection() {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(
                        null,
                        0,
                        20,
                        "serviceDate,desc"
                );
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
                        tuple("timeSnapshot.serviceDate", Sort.Direction.DESC),
                        tuple("id", Sort.Direction.DESC)
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
                        tuple("timeSnapshot.serviceDate", Sort.Direction.ASC),
                        tuple("id", Sort.Direction.ASC)
                );
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("운영자 serviceDate 정렬은 embedded 경로와 같은 방향의 ID 보조 정렬을 사용한다")
    void sortsStoreReservationsByEmbeddedServiceDateThenIdInSameDirection() {
        // given
        StoreReservationSearchRequest request = StoreReservationSearchRequest.from(
                null,
                null,
                0,
                20,
                "serviceDate,desc"
        );
        given(reservationRepository.findAllByStoreId(
                eq(22L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        reservationService.getStoreReservations(33L, 22L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByStoreId(eq(22L), pageable.capture());
        assertThat(pageable.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("timeSnapshot.serviceDate", Sort.Direction.DESC),
                        tuple("id", Sort.Direction.DESC)
                );
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
        given(reservationRepository.findAllByStoreIdAndTimeSnapshotServiceDateAndStatus(
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
                .findAllByStoreIdAndTimeSnapshotServiceDateAndStatus(
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
                Arguments.of(
                        "serviceDate,desc",
                        "timeSnapshot.serviceDate",
                        Sort.Direction.DESC
                ),
                Arguments.of(
                        "serviceDate,asc",
                        "timeSnapshot.serviceDate",
                        Sort.Direction.ASC
                )
        );
    }

    private void assertValidationFailure(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
