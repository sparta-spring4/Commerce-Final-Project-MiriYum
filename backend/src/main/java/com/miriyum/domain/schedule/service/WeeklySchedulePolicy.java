package com.miriyum.domain.schedule.service;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WeeklySchedulePolicy {

    private static final int MINUTES_PER_DAY = 1440;
    private static final int MINUTES_PER_WEEK = 10080;
    private static final Comparator<WeeklyInterval> INTERVAL_ORDER =
            Comparator.comparingInt(WeeklyInterval::weekStartMinute)
                    .thenComparingInt(WeeklyInterval::weekEndMinute)
                    .thenComparing(WeeklyInterval::kind);

    public List<WeeklyInterval> validateOperating(
            WeeklyOperatingHoursRequest request
    ) {
        List<WeeklyInterval> businessHours = new ArrayList<>();
        for (DailyOperatingScheduleRequest day : request.days()) {
            for (TimeRangeRequest range : day.businessHours()) {
                businessHours.add(normalize(
                        day.dayOfWeek(),
                        range,
                        ScheduleIntervalKind.BUSINESS_HOURS));
            }
        }
        requireNoOverlap(businessHours);

        List<WeeklyInterval> breaks = new ArrayList<>();
        for (DailyOperatingScheduleRequest day : request.days()) {
            for (TimeRangeRequest range : day.breakTimes()) {
                WeeklyInterval raw =
                        normalize(day.dayOfWeek(), range, ScheduleIntervalKind.BREAK_TIME);
                breaks.add(placeWithinOwnedBusinessDay(raw, businessHours));
            }
        }
        requireNoOverlap(breaks);

        List<WeeklyInterval> result = new ArrayList<>(businessHours);
        result.addAll(breaks);
        return result.stream().sorted(INTERVAL_ORDER).toList();
    }

    public List<WeeklyInterval> validateReservation(
            WeeklyReservationTimeSlotsRequest request,
            List<WeeklyInterval> operating
    ) {
        List<WeeklyInterval> businessHours = operating.stream()
                .filter(interval ->
                        interval.kind() == ScheduleIntervalKind.BUSINESS_HOURS)
                .toList();
        List<WeeklyInterval> breaks = operating.stream()
                .filter(interval -> interval.kind() == ScheduleIntervalKind.BREAK_TIME)
                .toList();

        List<WeeklyInterval> reservations = new ArrayList<>();
        for (DailyReservationSlotsRequest day : request.days()) {
            for (TimeRangeRequest range : day.slots()) {
                WeeklyInterval raw = normalize(
                        day.dayOfWeek(),
                        range,
                        ScheduleIntervalKind.RESERVATION_SLOT);
                WeeklyInterval placed =
                        placeWithinOwnedBusinessDay(raw, businessHours);
                if (breaks.stream().anyMatch(aBreak -> overlapsCyclic(placed, aBreak))) {
                    throw scheduleConflict();
                }
                reservations.add(placed);
            }
        }
        requireNoOverlap(reservations);
        return reservations.stream().sorted(INTERVAL_ORDER).toList();
    }

    private WeeklyInterval normalize(
            DayOfWeek day,
            TimeRangeRequest range,
            ScheduleIntervalKind kind
    ) {
        LocalTime startTime = range.startTime();
        LocalTime endTime = range.endTime();
        if (startTime.equals(endTime)) {
            throw scheduleConflict();
        }

        int dayStart = (day.getValue() - 1) * MINUTES_PER_DAY;
        int start = dayStart + minuteOfDay(startTime);
        boolean overnight = endTime.isBefore(startTime);
        int end = dayStart + minuteOfDay(endTime)
                + (overnight ? MINUTES_PER_DAY : 0);
        return new WeeklyInterval(
                day,
                startTime,
                endTime,
                overnight,
                kind,
                start,
                end);
    }

    private WeeklyInterval placeWithinOwnedBusinessDay(
            WeeklyInterval child,
            List<WeeklyInterval> businessHours
    ) {
        for (int shift : new int[] {0, MINUTES_PER_DAY}) {
            int childStart = child.weekStartMinute() + shift;
            int childEnd = child.weekEndMinute() + shift;
            boolean contained = businessHours.stream()
                    .filter(business ->
                            business.dayOfWeek() == child.dayOfWeek())
                    .anyMatch(business ->
                            business.weekStartMinute() <= childStart
                                    && childEnd <= business.weekEndMinute());
            if (contained) {
                return child.placedAt(childStart, childEnd);
            }
        }
        throw scheduleConflict();
    }

    private void requireNoOverlap(List<WeeklyInterval> intervals) {
        for (int first = 0; first < intervals.size(); first++) {
            for (int second = first + 1; second < intervals.size(); second++) {
                if (overlapsCyclic(intervals.get(first), intervals.get(second))) {
                    throw scheduleConflict();
                }
            }
        }
    }

    private boolean overlapsCyclic(
            WeeklyInterval first,
            WeeklyInterval second
    ) {
        for (int shift : new int[] {-MINUTES_PER_WEEK, 0, MINUTES_PER_WEEK}) {
            if (overlaps(
                    first.weekStartMinute(),
                    first.weekEndMinute(),
                    second.weekStartMinute() + shift,
                    second.weekEndMinute() + shift)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlaps(
            int firstStart,
            int firstEnd,
            int secondStart,
            int secondEnd
    ) {
        return firstStart < secondEnd && secondStart < firstEnd;
    }

    private int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private ServiceException scheduleConflict() {
        return new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
    }
}
