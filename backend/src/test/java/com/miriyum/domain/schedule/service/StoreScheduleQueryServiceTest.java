package com.miriyum.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.schedule.entity.OperatingScheduleEntry;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.ReservationScheduleEntry;
import com.miriyum.domain.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreScheduleQueryServiceTest {

    @Mock
    private StoreScheduleStateRepository stateRepository;

    @Mock
    private OperatingScheduleVersionRepository operatingRepository;

    @Mock
    private ReservationScheduleVersionRepository reservationRepository;

    private StoreScheduleQueryService service;

    @BeforeEach
    void setUp() {
        service = new StoreScheduleQueryService(
                stateRepository,
                operatingRepository,
                reservationRepository);
    }

    @Test
    void returnsOrderedPublicScheduleProjection() {
        StoreScheduleState state = mock(StoreScheduleState.class);
        OperatingScheduleVersion operating = mock(OperatingScheduleVersion.class);
        ReservationScheduleVersion reservation = mock(ReservationScheduleVersion.class);
        OperatingScheduleEntry businessHours = operatingEntry(
                ScheduleIntervalKind.BUSINESS_HOURS,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0));
        OperatingScheduleEntry breakTime = operatingEntry(
                ScheduleIntervalKind.BREAK_TIME,
                LocalTime.of(14, 0),
                LocalTime.of(15, 0));
        ReservationScheduleEntry slot = mock(ReservationScheduleEntry.class);
        given(slot.getDayOfWeek()).willReturn(DayOfWeek.MONDAY);
        given(slot.getStartTime()).willReturn(LocalTime.of(12, 0));
        given(slot.getEndTime()).willReturn(LocalTime.of(13, 0));
        given(state.getActiveOperatingScheduleVersionId()).willReturn(10L);
        given(state.getActiveReservationScheduleVersionId()).willReturn(20L);
        given(operating.getEntries()).willReturn(List.of(breakTime, businessHours));
        given(reservation.getEntries()).willReturn(List.of(slot));
        given(stateRepository.findById(7L)).willReturn(Optional.of(state));
        given(operatingRepository.findActiveByIdsWithEntries(
                List.of(10L), ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(operating));
        given(reservationRepository.findActiveByIdsWithEntries(
                List.of(20L), ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(reservation));

        var result = service.getPublicSchedules(7L);

        assertThat(result.operatingHours()).singleElement().satisfies(day -> {
            assertThat(day.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(day.businessHours()).singleElement().satisfies(range -> {
                assertThat(range.startTime()).isEqualTo(LocalTime.of(9, 0));
                assertThat(range.endTime()).isEqualTo(LocalTime.of(18, 0));
            });
            assertThat(day.breakTimes()).singleElement().satisfies(range ->
                    assertThat(range.startTime()).isEqualTo(LocalTime.of(14, 0)));
        });
        assertThat(result.reservationTimeSlots()).singleElement().satisfies(day ->
                assertThat(day.timeSlots()).singleElement().satisfies(range ->
                        assertThat(range.startTime()).isEqualTo(LocalTime.of(12, 0))));
    }

    private static OperatingScheduleEntry operatingEntry(
            ScheduleIntervalKind kind,
            LocalTime start,
            LocalTime end
    ) {
        OperatingScheduleEntry entry = mock(OperatingScheduleEntry.class);
        given(entry.getDayOfWeek()).willReturn(DayOfWeek.MONDAY);
        given(entry.getIntervalKind()).willReturn(kind);
        given(entry.getStartTime()).willReturn(start);
        given(entry.getEndTime()).willReturn(end);
        return entry;
    }
}
