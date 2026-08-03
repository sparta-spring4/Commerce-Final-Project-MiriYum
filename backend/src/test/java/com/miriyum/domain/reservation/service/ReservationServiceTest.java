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
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.schedule.dto.StoreReservationWindowResult;
import com.miriyum.domain.store.schedule.service.StoreScheduleService;
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

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 3);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);

    @Mock
    private StoreScheduleService storeScheduleService;

    @Mock
    private ReservationTimePolicyVersionRepository timePolicyRepository;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                storeScheduleService,
                timePolicyRepository,
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
        given(timePolicyRepository.findEffectiveActiveByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(
                activePolicy(1L, 30, 90, 30),
                activePolicy(2L, 30, 60, 15)
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
                        ReservationTimeResolutionStatus.RESOLVED,
                        ReservationTimeResolutionStatus.UNAVAILABLE,
                        ReservationTimeResolutionStatus.RESOLVED
                );
        assertThat(results.get(0).timeSnapshot().getOccupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-03T10:15:00Z"));
        assertThat(results.get(1).timeSnapshot().getServiceEndAt())
                .isEqualTo(Instant.parse("2026-08-03T10:30:00Z"));
        assertThat(results.get(1).timeSnapshot().getOccupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-03T11:00:00Z"));
        assertThat(results.get(2).timeSnapshot()).isNull();
        assertThat(results.get(3).timeSnapshot().getOccupancyEndAt())
                .isEqualTo(results.get(0).timeSnapshot().getOccupancyEndAt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        then(timePolicyRepository).should(times(1)).findEffectiveActiveByStoreIds(
                idsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(NOW)
        );
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
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
        then(timePolicyRepository).should(never()).findEffectiveActiveByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
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
        given(timePolicyRepository.findEffectiveActiveByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 0)));

        List<ReservationTimeResolutionResult> results = reservationService
                .resolveReservationTimes(
                        storeIds,
                        new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
                );

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
            assertThat(result.timeSnapshot()).isNull();
        });
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
        given(timePolicyRepository.findEffectiveActiveByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 0)));

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
        assertThat(explicit.timeSnapshot().getStartAt())
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        then(timePolicyRepository).should(times(2)).findEffectiveActiveByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(NOW)
        );
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
        given(timePolicyRepository.findEffectiveActiveByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of());

        assertThat(reservationService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).singleElement().satisfies(result ->
                assertThat(result.status())
                        .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE));
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
        policy.activate(NOW.minusSeconds(1));
        return policy;
    }
}
