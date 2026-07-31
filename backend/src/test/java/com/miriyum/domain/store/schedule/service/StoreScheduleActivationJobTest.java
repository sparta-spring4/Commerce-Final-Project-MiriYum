package com.miriyum.domain.store.schedule.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.ReservationScheduleVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreScheduleActivationJobTest {

    @Mock
    private OperatingScheduleVersionRepository operatingRepository;

    @Mock
    private ReservationScheduleVersionRepository reservationRepository;

    @Mock
    private StoreScheduleService scheduleService;

    @Test
    void dueCandidatesAreForwardedInRepositoryOrder() {
        Instant now = Instant.parse("2026-07-31T03:00:00Z");
        OperatingScheduleVersion first = Mockito.mock(
                OperatingScheduleVersion.class);
        OperatingScheduleVersion second = Mockito.mock(
                OperatingScheduleVersion.class);
        ReservationScheduleVersion reservation = Mockito.mock(
                ReservationScheduleVersion.class);
        given(first.getId()).willReturn(11L);
        given(second.getId()).willReturn(12L);
        given(reservation.getId()).willReturn(21L);
        given(operatingRepository
                .findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        ScheduleVersionStatus.SCHEDULED,
                        now))
                .willReturn(List.of(first, second));
        given(reservationRepository
                .findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        ScheduleVersionStatus.SCHEDULED,
                        now))
                .willReturn(List.of(reservation));
        StoreScheduleActivationJob job = new StoreScheduleActivationJob(
                operatingRepository,
                reservationRepository,
                scheduleService,
                Clock.fixed(now, ZoneOffset.UTC));

        job.activateDueSchedules();

        InOrder order = Mockito.inOrder(scheduleService);
        order.verify(scheduleService).activateDueOperating(11L);
        order.verify(scheduleService).activateDueOperating(12L);
        order.verify(scheduleService).activateDueReservation(21L);
        then(scheduleService).shouldHaveNoMoreInteractions();
    }

    @Test
    void oneCandidateFailureDoesNotBlockRemainingCandidates() {
        Instant now = Instant.parse("2026-07-31T03:00:00Z");
        OperatingScheduleVersion first = Mockito.mock(
                OperatingScheduleVersion.class);
        OperatingScheduleVersion second = Mockito.mock(
                OperatingScheduleVersion.class);
        ReservationScheduleVersion reservation = Mockito.mock(
                ReservationScheduleVersion.class);
        given(first.getId()).willReturn(11L);
        given(second.getId()).willReturn(12L);
        given(reservation.getId()).willReturn(21L);
        given(operatingRepository
                .findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        ScheduleVersionStatus.SCHEDULED,
                        now))
                .willReturn(List.of(first, second));
        given(reservationRepository
                .findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        ScheduleVersionStatus.SCHEDULED,
                        now))
                .willReturn(List.of(reservation));
        willThrow(new IllegalStateException("temporary failure"))
                .given(scheduleService).activateDueOperating(11L);
        StoreScheduleActivationJob job = new StoreScheduleActivationJob(
                operatingRepository,
                reservationRepository,
                scheduleService,
                Clock.fixed(now, ZoneOffset.UTC));

        job.activateDueSchedules();

        then(scheduleService).should().activateDueOperating(12L);
        then(scheduleService).should().activateDueReservation(21L);
    }
}
