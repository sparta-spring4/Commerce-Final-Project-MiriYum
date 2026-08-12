package com.miriyum.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.schedule.dto.storeoperator.DailyOperatingScheduleRequest;
import com.miriyum.domain.schedule.dto.storeoperator.DailyReservationSlotsRequest;
import com.miriyum.domain.schedule.dto.storeoperator.TimeRangeRequest;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyOperatingHoursRequest;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyReservationTimeSlotsRequest;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.WeeklyInterval;
import com.miriyum.global.exception.ServiceException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeeklySchedulePolicyTest {

    private final WeeklySchedulePolicy policy = new WeeklySchedulePolicy();

    @Test
    void normalizesMondayOvernightRangeToTuesdayEnd() {
        WeeklyOperatingHoursRequest request = operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(22, 0, 2, 0))),
                Map.of());

        List<WeeklyInterval> intervals = policy.validateOperating(request);

        assertThat(intervals).singleElement().satisfies(interval -> {
            assertThat(interval.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(interval.overnight()).isTrue();
            assertThat(interval.weekStartMinute()).isEqualTo(1320);
            assertThat(interval.weekEndMinute()).isEqualTo(1560);
            assertThat(interval.kind()).isEqualTo(ScheduleIntervalKind.BUSINESS_HOURS);
        });
    }

    @Test
    void rejectsEqualStartAndEnd() {
        WeeklyOperatingHoursRequest request = operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(9, 0, 9, 0))),
                Map.of());

        assertScheduleConflict(() -> policy.validateOperating(request));
    }

    @Test
    void allowsAdjacentHalfOpenBusinessRanges() {
        WeeklyOperatingHoursRequest request = operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(
                        range(9, 0, 12, 0),
                        range(12, 0, 18, 0))),
                Map.of());

        assertThat(policy.validateOperating(request))
                .filteredOn(interval ->
                        interval.kind() == ScheduleIntervalKind.BUSINESS_HOURS)
                .hasSize(2);
    }

    @Test
    void rejectsOverlapAcrossMidnight() {
        WeeklyOperatingHoursRequest request = operatingWeek(
                Map.of(
                        DayOfWeek.MONDAY, List.of(range(22, 0, 2, 0)),
                        DayOfWeek.TUESDAY, List.of(range(1, 0, 3, 0))),
                Map.of());

        assertScheduleConflict(() -> policy.validateOperating(request));
    }

    @Test
    void rejectsSundayOvernightOverlapWithMonday() {
        WeeklyOperatingHoursRequest request = operatingWeek(
                Map.of(
                        DayOfWeek.SUNDAY, List.of(range(22, 0, 2, 0)),
                        DayOfWeek.MONDAY, List.of(range(1, 0, 3, 0))),
                Map.of());

        assertScheduleConflict(() -> policy.validateOperating(request));
    }

    @Test
    void rejectsBreakOutsideBusinessRange() {
        WeeklyOperatingHoursRequest request = operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(9, 0, 18, 0))),
                Map.of(DayOfWeek.MONDAY, List.of(range(17, 30, 19, 0))));

        assertScheduleConflict(() -> policy.validateOperating(request));
    }

    @Test
    void rejectsOverlappingBreaks() {
        WeeklyOperatingHoursRequest request = operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(9, 0, 18, 0))),
                Map.of(DayOfWeek.MONDAY, List.of(
                        range(12, 0, 13, 0),
                        range(12, 30, 13, 30))));

        assertScheduleConflict(() -> policy.validateOperating(request));
    }

    @Test
    void acceptsReservationInsideBusinessAndOutsideBreak() {
        List<WeeklyInterval> operating = policy.validateOperating(operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(18, 0, 2, 0))),
                Map.of(DayOfWeek.MONDAY, List.of(range(23, 0, 23, 30)))));
        WeeklyReservationTimeSlotsRequest request = reservationWeek(
                Map.of(DayOfWeek.MONDAY, List.of(
                        range(18, 0, 22, 0),
                        range(23, 30, 1, 0))));

        assertThat(policy.validateReservation(request, operating)).hasSize(2);
    }

    @Test
    void placesEarlyMorningReservationOnFollowingDayOfOwnedBusinessDay() {
        List<WeeklyInterval> operating = policy.validateOperating(operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(18, 0, 2, 0))),
                Map.of()));
        WeeklyReservationTimeSlotsRequest request = reservationWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(1, 0, 2, 0))));

        assertThat(policy.validateReservation(request, operating))
                .singleElement()
                .satisfies(interval -> {
                    assertThat(interval.weekStartMinute()).isEqualTo(1500);
                    assertThat(interval.weekEndMinute()).isEqualTo(1560);
                });
    }

    @Test
    void rejectsReservationCrossingBreak() {
        List<WeeklyInterval> operating = policy.validateOperating(operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(18, 0, 2, 0))),
                Map.of(DayOfWeek.MONDAY, List.of(range(23, 0, 23, 30)))));
        WeeklyReservationTimeSlotsRequest request = reservationWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(22, 30, 0, 0))));

        assertScheduleConflict(() -> policy.validateReservation(request, operating));
    }

    @Test
    void rejectsReservationOutsideBusinessRange() {
        List<WeeklyInterval> operating = policy.validateOperating(operatingWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(18, 0, 2, 0))),
                Map.of()));
        WeeklyReservationTimeSlotsRequest request = reservationWeek(
                Map.of(DayOfWeek.MONDAY, List.of(range(17, 30, 18, 30))));

        assertScheduleConflict(() -> policy.validateReservation(request, operating));
    }

    private WeeklyOperatingHoursRequest operatingWeek(
            Map<DayOfWeek, List<TimeRangeRequest>> businessByDay,
            Map<DayOfWeek, List<TimeRangeRequest>> breaksByDay
    ) {
        return new WeeklyOperatingHoursRequest(Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyOperatingScheduleRequest(
                        day,
                        businessByDay.getOrDefault(day, List.of()),
                        breaksByDay.getOrDefault(day, List.of())))
                .toList());
    }

    private WeeklyReservationTimeSlotsRequest reservationWeek(
            Map<DayOfWeek, List<TimeRangeRequest>> slotsByDay
    ) {
        return new WeeklyReservationTimeSlotsRequest(Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyReservationSlotsRequest(
                        day,
                        slotsByDay.getOrDefault(day, List.of())))
                .toList());
    }

    private TimeRangeRequest range(
            int startHour,
            int startMinute,
            int endHour,
            int endMinute
    ) {
        return new TimeRangeRequest(
                LocalTime.of(startHour, startMinute),
                LocalTime.of(endHour, endMinute));
    }

    private void assertScheduleConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.SCHEDULE_CONFLICT);
    }
}
