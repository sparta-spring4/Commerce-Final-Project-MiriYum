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
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.service.StoreScheduleService;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationTimeResolutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 3);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);

    @Mock
    private StoreScheduleService storeScheduleService;

    @Mock
    private StoreServiceIntervalValidationService intervalValidationService;

    @Mock
    private ReservationTimePolicyVersionRepository timePolicyRepository;

    private ReservationTimeResolutionService service;

    @BeforeEach
    void setUp() {
        service = new ReservationTimeResolutionService(
                storeScheduleService,
                intervalValidationService,
                timePolicyRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void preservesInputOrderDuplicatesAndFailsClosedPerStore() {
        List<Long> storeIds = List.of(2L, 1L, 3L, 2L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds, SERVICE_DATE, START_TIME)).willReturn(List.of(
                StoreReservationWindowResult.accepting(
                        2L, "Asia/Seoul", requestedAt.minusHours(1), requestedAt.plusHours(1)),
                StoreReservationWindowResult.accepting(
                        1L, "Asia/Seoul", requestedAt, requestedAt.plusHours(1)),
                StoreReservationWindowResult.notAccepting(3L),
                StoreReservationWindowResult.accepting(
                        2L, "Asia/Seoul", requestedAt.minusHours(1), requestedAt.plusHours(1))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)))
                .willReturn(List.of(
                        activePolicy(1L, 30, 90, 30),
                        activePolicy(2L, 30, 60, 15)));
        StoreServiceIntervalRequest store2 = new StoreServiceIntervalRequest(
                2L, Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
        StoreServiceIntervalRequest store1 = new StoreServiceIntervalRequest(
                1L, Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:30:00Z"));
        given(intervalValidationService.validateServiceIntervals(
                List.of(store2, store1, store2))).willReturn(List.of(
                StoreServiceIntervalResult.of(store2, true),
                StoreServiceIntervalResult.of(store1, false),
                StoreServiceIntervalResult.of(store2, true)));

        List<ReservationTimeResolutionResult> results = service.resolveReservationTimes(
                storeIds, new ReservationTimeRequest(SERVICE_DATE, START_TIME, null));

        assertThat(results).extracting(ReservationTimeResolutionResult::storeId)
                .containsExactly(2L, 1L, 3L, 2L);
        assertThat(results).extracting(ReservationTimeResolutionResult::status)
                .containsExactly(
                        ReservationTimeResolutionStatus.RESOLVED,
                        ReservationTimeResolutionStatus.UNAVAILABLE,
                        ReservationTimeResolutionStatus.UNAVAILABLE,
                        ReservationTimeResolutionStatus.RESOLVED);
        assertThat(results.getFirst().time().occupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-03T10:15:00Z"));
    }

    @Test
    void requiresExplicitOffsetForAmbiguousDstStart() {
        LocalDate serviceDate = LocalDate.of(2026, 10, 25);
        LocalTime startTime = LocalTime.of(2, 30);
        LocalDateTime requestedAt = LocalDateTime.of(serviceDate, startTime);
        List<Long> storeIds = List.of(1L);
        given(storeScheduleService.resolveReservationWindows(storeIds, serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        1L, "Europe/Paris", requestedAt.minusMinutes(30),
                        requestedAt.plusMinutes(30))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)))
                .willReturn(List.of(activePolicy(1L, 30, 60, 0)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L, Instant.parse("2026-10-25T00:30:00Z"),
                Instant.parse("2026-10-25T01:30:00Z"));
        given(intervalValidationService.validateServiceIntervals(List.of(interval)))
                .willReturn(List.of(StoreServiceIntervalResult.of(interval, true)));

        ReservationTimeResolutionResult ambiguous = service.resolveReservationTimes(
                storeIds, new ReservationTimeRequest(serviceDate, startTime, null)).getFirst();
        ReservationTimeResolutionResult explicit = service.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(
                        serviceDate, startTime, ZoneOffset.ofHours(2))).getFirst();

        assertThat(ambiguous.status()).isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
        assertThat(explicit.status()).isEqualTo(ReservationTimeResolutionStatus.RESOLVED);
        assertThat(explicit.time().startAt())
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        then(intervalValidationService).should(times(1))
                .validateServiceIntervals(List.of(interval));
    }

    @Test
    void malformedServiceIntervalResponseFailsClosed() {
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        List<Long> storeIds = List.of(1L);
        given(storeScheduleService.resolveReservationWindows(
                storeIds, SERVICE_DATE, START_TIME))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        1L, "Asia/Seoul", requestedAt, requestedAt.plusHours(1))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)))
                .willReturn(List.of(activePolicy(1L, 30, 60, 0)));
        given(intervalValidationService.validateServiceIntervals(
                org.mockito.ArgumentMatchers.anyList())).willReturn(null);

        assertThat(service.resolveReservationTimes(
                storeIds, new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)))
                .singleElement()
                .extracting(ReservationTimeResolutionResult::status)
                .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
    }

    @Test
    void emptyInputDoesNotCallDependencies() {
        assertThat(service.resolveReservationTimes(
                List.of(), new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)))
                .isEmpty();

        then(storeScheduleService).shouldHaveNoInteractions();
        then(timePolicyRepository).shouldHaveNoInteractions();
        then(intervalValidationService).should(never())
                .validateServiceIntervals(org.mockito.ArgumentMatchers.anyList());
    }

    private static ReservationTimePolicyVersion activePolicy(
            long storeId,
            int slotIntervalMinutes,
            int serviceDurationMinutes,
            int turnoverDurationMinutes
    ) {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                storeId, 1L, slotIntervalMinutes,
                serviceDurationMinutes, turnoverDurationMinutes);
        policy.activate(NOW.minusSeconds(1), "active policy");
        return policy;
    }
}
