package com.miriyum.domain.schedule.dto.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyScheduleRequestTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsExactlySevenDistinctOperatingDays() {
        WeeklyOperatingHoursRequest request =
                new WeeklyOperatingHoursRequest(allOperatingDays());

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsOperatingWeekWithOnlySixDays() {
        WeeklyOperatingHoursRequest request =
                new WeeklyOperatingHoursRequest(allOperatingDays().subList(0, 6));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("days");
    }

    @Test
    void rejectsOperatingWeekWithDuplicateAndMissingDay() {
        List<DailyOperatingScheduleRequest> days =
                new ArrayList<>(allOperatingDays());
        days.set(6, days.get(0));
        WeeklyOperatingHoursRequest request = new WeeklyOperatingHoursRequest(days);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("eachDayExactlyOnce");
    }

    @Test
    void rejectsReservationWeekWithNullTimeBoundary() {
        List<DailyReservationSlotsRequest> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyReservationSlotsRequest(day, List.of()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        days.set(0, new DailyReservationSlotsRequest(
                DayOfWeek.MONDAY,
                List.of(new TimeRangeRequest(null, LocalTime.of(12, 0)))));

        WeeklyReservationTimeSlotsRequest request =
                new WeeklyReservationTimeSlotsRequest(days);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("days[0].slots[0].startTime");
    }

    @Test
    void rejectsTimeWithSecondPrecision() {
        List<DailyReservationSlotsRequest> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyReservationSlotsRequest(day, List.of()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        days.set(0, new DailyReservationSlotsRequest(
                DayOfWeek.MONDAY,
                List.of(new TimeRangeRequest(
                        LocalTime.of(9, 0, 30),
                        LocalTime.of(10, 0)))));

        assertThat(validator.validate(new WeeklyReservationTimeSlotsRequest(days)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("days[0].slots[0].minutePrecision");
    }

    @Test
    void rejectsNullDayAndRangeElements() {
        List<DailyOperatingScheduleRequest> days =
                new ArrayList<>(allOperatingDays());
        days.set(0, null);
        days.set(1, new DailyOperatingScheduleRequest(
                DayOfWeek.TUESDAY,
                Arrays.asList((TimeRangeRequest) null),
                List.of()));

        assertThat(validator.validate(new WeeklyOperatingHoursRequest(days)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "days[0].<list element>",
                        "days[1].businessHours[0].<list element>");
    }

    @Test
    void rejectsMoreThanFortyEightIntervalsPerDay() {
        TimeRangeRequest range = new TimeRangeRequest(
                LocalTime.of(9, 0),
                LocalTime.of(10, 0));
        List<TimeRangeRequest> tooMany = new ArrayList<>();
        for (int index = 0; index < 49; index++) {
            tooMany.add(range);
        }

        DailyOperatingScheduleRequest operating =
                new DailyOperatingScheduleRequest(
                        DayOfWeek.MONDAY,
                        tooMany,
                        tooMany);
        DailyReservationSlotsRequest reservation =
                new DailyReservationSlotsRequest(
                        DayOfWeek.MONDAY,
                        tooMany);

        assertThat(validator.validate(operating))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("businessHours", "breakTimes");
        assertThat(validator.validate(reservation))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("slots");
    }

    private List<DailyOperatingScheduleRequest> allOperatingDays() {
        return Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyOperatingScheduleRequest(
                        day,
                        List.of(),
                        List.of()))
                .toList();
    }
}
