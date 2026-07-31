package com.miriyum.domain.store.schedule.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreScheduleResponseTest {

    @Test
    void operatingResponseReturnsCanonicalSevenDaysAndOvernightTimes() {
        OperatingScheduleVersion version = OperatingScheduleVersion.create(
                7L,
                3L,
                List.of(
                        interval(
                                DayOfWeek.FRIDAY,
                                23,
                                30,
                                0,
                                30,
                                ScheduleIntervalKind.BREAK_TIME,
                                7170,
                                7230),
                        interval(
                                DayOfWeek.FRIDAY,
                                18,
                                0,
                                2,
                                0,
                                ScheduleIntervalKind.BUSINESS_HOURS,
                                6840,
                                7320)));

        OperatingHoursResponse response = OperatingHoursResponse.from(version);

        assertThat(version.getStoreId()).isEqualTo(7L);
        assertThat(version.getVersionNumber()).isEqualTo(3L);
        assertThat(version.getEntries()).hasSize(2);
        assertThat(response.version()).isEqualTo(3);
        assertThat(response.days()).extracting(DailyOperatingScheduleRequest::dayOfWeek)
                .containsExactly(DayOfWeek.values());
        DailyOperatingScheduleRequest friday = response.days().get(4);
        assertThat(friday.businessHours()).containsExactly(
                new TimeRangeRequest(LocalTime.of(18, 0), LocalTime.of(2, 0)));
        assertThat(friday.breakTimes()).containsExactly(
                new TimeRangeRequest(LocalTime.of(23, 30), LocalTime.of(0, 30)));
    }

    @Test
    void reservationResponseReturnsCanonicalSevenDays() {
        ReservationScheduleVersion version = ReservationScheduleVersion.create(
                7L,
                2L,
                20L,
                List.of(interval(
                        DayOfWeek.MONDAY,
                        1,
                        0,
                        2,
                        0,
                        ScheduleIntervalKind.RESERVATION_SLOT,
                        1500,
                        1560)));

        ReservationTimeSlotsResponse response =
                ReservationTimeSlotsResponse.from(version);

        assertThat(version.getStoreId()).isEqualTo(7L);
        assertThat(version.getVersionNumber()).isEqualTo(2L);
        assertThat(version.getValidatedOperatingVersionId()).isEqualTo(20L);
        assertThat(version.getEntries()).hasSize(1);
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.days()).extracting(DailyReservationSlotsRequest::dayOfWeek)
                .containsExactly(DayOfWeek.values());
        assertThat(response.days().get(0).slots()).containsExactly(
                new TimeRangeRequest(LocalTime.of(1, 0), LocalTime.of(2, 0)));
    }

    private WeeklyInterval interval(
            DayOfWeek day,
            int startHour,
            int startMinute,
            int endHour,
            int endMinute,
            ScheduleIntervalKind kind,
            int weekStart,
            int weekEnd
    ) {
        return new WeeklyInterval(
                day,
                LocalTime.of(startHour, startMinute),
                LocalTime.of(endHour, endMinute),
                weekEnd > day.getValue() * 1440,
                kind,
                weekStart,
                weekEnd);
    }
}
