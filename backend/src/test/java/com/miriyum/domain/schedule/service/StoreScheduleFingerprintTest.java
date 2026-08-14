package com.miriyum.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.schedule.dto.storeoperator.DailyOperatingScheduleRequest;
import com.miriyum.domain.schedule.dto.storeoperator.DailyReservationSlotsRequest;
import com.miriyum.domain.schedule.dto.storeoperator.TimeRangeRequest;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyOperatingHoursRequest;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyReservationTimeSlotsRequest;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationRequest;
import com.miriyum.domain.schedule.model.PublicationMode;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreScheduleFingerprintTest {

    @Test
    void operatingFingerprintUsesCanonicalStoreOperatorNamespace() {
        WeeklyOperatingHoursRequest request = new WeeklyOperatingHoursRequest(List.of(
                new DailyOperatingScheduleRequest(DayOfWeek.MONDAY, List.of(), List.of())));

        assertThat(StoreScheduleFingerprint.forOperating(7L, request)).isEqualTo(
                RequestFingerprint.of(
                        "PUT|/api/v1/store-operators/stores/{storeId}/operating-hours|"
                                + "storeId=1:7|day=6:MONDAY|"
                                + "MONDAY.businessHours.size=1:0|"
                                + "MONDAY.breakTimes.size=1:0|"));
    }

    @Test
    void operatingFingerprintIgnoresDayAndRangeArrayOrder() {
        WeeklyOperatingHoursRequest first = operatingRequest(false, false);
        WeeklyOperatingHoursRequest reordered = operatingRequest(true, true);

        assertThat(StoreScheduleFingerprint.forOperating(7L, first))
                .isEqualTo(StoreScheduleFingerprint.forOperating(7L, reordered));
    }

    @Test
    void operatingFingerprintChangesWithEndTime() {
        WeeklyOperatingHoursRequest original = operatingRequest(false, false);
        List<DailyOperatingScheduleRequest> changedDays =
                new ArrayList<>(original.days());
        changedDays.set(0, new DailyOperatingScheduleRequest(
                DayOfWeek.MONDAY,
                List.of(range(9, 0, 17, 0)),
                List.of()));

        assertThat(StoreScheduleFingerprint.forOperating(7L, original))
                .isNotEqualTo(StoreScheduleFingerprint.forOperating(
                        7L,
                        new WeeklyOperatingHoursRequest(changedDays)));
    }

    @Test
    void operatingFingerprintIncludesStoreId() {
        WeeklyOperatingHoursRequest request = operatingRequest(false, false);

        assertThat(StoreScheduleFingerprint.forOperating(7L, request))
                .isNotEqualTo(StoreScheduleFingerprint.forOperating(8L, request));
    }

    @Test
    void reservationFingerprintIgnoresDayOrder() {
        List<DailyReservationSlotsRequest> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyReservationSlotsRequest(
                        day,
                        day == DayOfWeek.MONDAY
                                ? List.of(range(18, 0, 20, 0))
                                : List.of()))
                .toList();
        List<DailyReservationSlotsRequest> reversed = new ArrayList<>(days);
        Collections.reverse(reversed);

        assertThat(StoreScheduleFingerprint.forReservation(
                7L,
                new WeeklyReservationTimeSlotsRequest(days)))
                .isEqualTo(StoreScheduleFingerprint.forReservation(
                        7L,
                        new WeeklyReservationTimeSlotsRequest(reversed)));
    }

    @Test
    void publicationFingerprintsUsePluralLifecycleRoute() {
        SchedulePublicationRequest request = new SchedulePublicationRequest(
                PublicationMode.IMMEDIATE, null, "publish now");

        assertThat(StoreScheduleFingerprint.forOperatingPublication(7L, 3L, request))
                .isEqualTo(RequestFingerprint.of(
                        "POST|/api/v1/store-operators/stores/{storeId}"
                                + "/operating-hours/{version}/publications|"
                                + "storeId=1:7|version=1:3|publicationMode=9:IMMEDIATE|"
                                + "effectiveAt=0:|changeReason=11:publish now|"));
        assertThat(StoreScheduleFingerprint.forReservationPublication(7L, 3L, request))
                .isEqualTo(RequestFingerprint.of(
                        "POST|/api/v1/store-operators/stores/{storeId}/"
                                + "reservation-time-slots/{version}/publications|"
                                + "storeId=1:7|version=1:3|publicationMode=9:IMMEDIATE|"
                                + "effectiveAt=0:|changeReason=11:publish now|"));
    }

    private WeeklyOperatingHoursRequest operatingRequest(
            boolean reverseDays,
            boolean reverseMondayRanges
    ) {
        List<TimeRangeRequest> monday = new ArrayList<>(List.of(
                range(9, 0, 12, 0),
                range(13, 0, 18, 0)));
        if (reverseMondayRanges) {
            Collections.reverse(monday);
        }
        List<DailyOperatingScheduleRequest> days =
                new ArrayList<>(Arrays.stream(DayOfWeek.values())
                        .map(day -> new DailyOperatingScheduleRequest(
                                day,
                                day == DayOfWeek.MONDAY ? monday : List.of(),
                                List.of()))
                        .toList());
        if (reverseDays) {
            Collections.reverse(days);
        }
        return new WeeklyOperatingHoursRequest(days);
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
}
