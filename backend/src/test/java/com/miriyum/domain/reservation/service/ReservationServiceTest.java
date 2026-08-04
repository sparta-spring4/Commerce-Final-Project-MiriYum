package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowResult;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 3);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);

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
                Clock.fixed(NOW, ZoneOffset.UTC)
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
}
