package com.miriyum.domain.schedule.service;

import com.miriyum.domain.schedule.dto.storeoperator.DailyOperatingScheduleRequest;
import com.miriyum.domain.schedule.dto.storeoperator.DailyReservationSlotsRequest;
import com.miriyum.domain.schedule.dto.storeoperator.TimeRangeRequest;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyOperatingHoursRequest;
import com.miriyum.domain.schedule.dto.storeoperator.WeeklyReservationTimeSlotsRequest;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationRequest;
import com.miriyum.domain.schedule.dto.storeoperator.SchedulePublicationCancellationRequest;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

public final class StoreScheduleFingerprint {

    private static final Comparator<TimeRangeRequest> RANGE_ORDER =
            Comparator.comparing(TimeRangeRequest::startTime)
                    .thenComparing(TimeRangeRequest::endTime);

    private StoreScheduleFingerprint() {
    }

    public static String forOperating(
            long storeId,
            WeeklyOperatingHoursRequest request
    ) {
        StringBuilder canonical = new StringBuilder(
                "PUT|/api/v1/store-operators/stores/{storeId}/operating-hours|");
        append(canonical, "storeId", Long.toString(storeId));
        request.days().stream()
                .sorted(Comparator.comparingInt(day ->
                        day.dayOfWeek().getValue()))
                .forEach(day -> appendOperatingDay(canonical, day));
        return RequestFingerprint.of(canonical.toString());
    }

    public static String forReservation(
            long storeId,
            WeeklyReservationTimeSlotsRequest request
    ) {
        StringBuilder canonical = new StringBuilder(
                "PUT|/api/v1/store-operators/stores/{storeId}/reservation-time-slots|");
        append(canonical, "storeId", Long.toString(storeId));
        request.days().stream()
                .sorted(Comparator.comparingInt(day ->
                        day.dayOfWeek().getValue()))
                .forEach(day -> appendReservationDay(canonical, day));
        return RequestFingerprint.of(canonical.toString());
    }

    public static String forOperatingPublication(
            long storeId,
            long version,
            SchedulePublicationRequest request
    ) {
        StringBuilder canonical = new StringBuilder(
                "POST|/api/v1/store-operators/stores/{storeId}"
                        + "/operating-hours/{version}/publication|");
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "version", Long.toString(version));
        append(canonical, "publicationMode", request.publicationMode().name());
        append(canonical, "effectiveAt", request.effectiveAt() == null
                ? ""
                : request.effectiveAt().toInstant().toString());
        append(canonical, "changeReason", request.changeReason());
        return RequestFingerprint.of(canonical.toString());
    }

    public static String forReservationPublication(
            long storeId,
            long version,
            SchedulePublicationRequest request
    ) {
        return forPublication("reservation-time-slots", storeId, version, request);
    }

    public static String forCancellation(
            String streamPath,
            long storeId,
            long version,
            SchedulePublicationCancellationRequest request
    ) {
        StringBuilder canonical = new StringBuilder(
                "POST|/api/v1/store-operators/stores/{storeId}/"
                        + streamPath + "/{version}/publication-cancellation|");
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "version", Long.toString(version));
        append(canonical, "changeReason", request.changeReason());
        return RequestFingerprint.of(canonical.toString());
    }

    private static String forPublication(
            String streamPath,
            long storeId,
            long version,
            SchedulePublicationRequest request
    ) {
        StringBuilder canonical = new StringBuilder(
                "POST|/api/v1/store-operators/stores/{storeId}/"
                        + streamPath + "/{version}/publication|");
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "version", Long.toString(version));
        append(canonical, "publicationMode", request.publicationMode().name());
        append(canonical, "effectiveAt", request.effectiveAt() == null
                ? ""
                : request.effectiveAt().toInstant().toString());
        append(canonical, "changeReason", request.changeReason());
        return RequestFingerprint.of(canonical.toString());
    }

    private static void appendOperatingDay(
            StringBuilder canonical,
            DailyOperatingScheduleRequest day
    ) {
        String prefix = day.dayOfWeek().name();
        append(canonical, "day", prefix);
        appendRanges(canonical, prefix + ".businessHours", day.businessHours());
        appendRanges(canonical, prefix + ".breakTimes", day.breakTimes());
    }

    private static void appendReservationDay(
            StringBuilder canonical,
            DailyReservationSlotsRequest day
    ) {
        String prefix = day.dayOfWeek().name();
        append(canonical, "day", prefix);
        appendRanges(canonical, prefix + ".slots", day.slots());
    }

    private static void appendRanges(
            StringBuilder canonical,
            String fieldName,
            List<TimeRangeRequest> ranges
    ) {
        List<TimeRangeRequest> sorted = ranges.stream().sorted(RANGE_ORDER).toList();
        append(canonical, fieldName + ".size", Integer.toString(sorted.size()));
        for (int index = 0; index < sorted.size(); index++) {
            TimeRangeRequest range = sorted.get(index);
            append(canonical, fieldName + "[" + index + "].start",
                    canonicalTime(range.startTime()));
            append(canonical, fieldName + "[" + index + "].end",
                    canonicalTime(range.endTime()));
        }
    }

    private static String canonicalTime(LocalTime time) {
        return String.format(
                java.util.Locale.ROOT,
                "%02d:%02d",
                time.getHour(),
                time.getMinute());
    }

    private static void append(
            StringBuilder canonical,
            String fieldName,
            String value
    ) {
        canonical.append(fieldName)
                .append('=')
                .append(value.length())
                .append(':')
                .append(value)
                .append('|');
    }
}
