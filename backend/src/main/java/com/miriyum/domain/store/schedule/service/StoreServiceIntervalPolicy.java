package com.miriyum.domain.store.schedule.service;

import com.miriyum.domain.store.closure.entity.RegularClosureVersion;
import com.miriyum.domain.store.closure.entity.TemporaryClosure;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import java.time.*;
import java.time.zone.ZoneRules;
import java.util.List;

public final class StoreServiceIntervalPolicy {
    public record Sources(
            boolean storeAccepting,
            String timeZoneId,
            List<WeeklyInterval> operatingIntervals,
            List<WeeklyInterval> reservationIntervals,
            RegularClosureVersion regularClosure,
            List<TemporaryClosure> temporaryClosures
    ) { }

    public boolean accepts(StoreServiceIntervalRequest request, Sources sources) {
        if (sources == null || !sources.storeAccepting() || sources.timeZoneId() == null
                || sources.operatingIntervals() == null || sources.reservationIntervals() == null
                || sources.regularClosure() == null || sources.temporaryClosures() == null) return false;
        final ZoneId zone;
        try { zone = ZoneId.of(sources.timeZoneId()); } catch (DateTimeException ex) { return false; }
        if (match(request.startAt(), request.startAt().plusNanos(1), zone,
                sources.reservationIntervals(), ScheduleIntervalKind.RESERVATION_SLOT, true) != Match.YES) return false;
        if (match(request.startAt(), request.serviceEndAt(), zone,
                sources.operatingIntervals(), ScheduleIntervalKind.BUSINESS_HOURS, true) != Match.YES) return false;
        if (match(request.startAt(), request.serviceEndAt(), zone,
                sources.operatingIntervals(), ScheduleIntervalKind.BREAK_TIME, false) != Match.NO) return false;
        LocalDate first = request.startAt().atZone(zone).toLocalDate();
        LocalDate last = request.serviceEndAt().minusNanos(1).atZone(zone).toLocalDate();
        if (!hasStrictDayBoundaries(first, last, zone)) return false;
        if (first.datesUntil(last.plusDays(1)).anyMatch(sources.regularClosure()::isClosedOn)) return false;
        return sources.temporaryClosures().stream()
                .noneMatch(c -> c.getCancelledAt() == null && c.overlaps(request.startAt(), request.serviceEndAt()));
    }

    private Match match(Instant start, Instant end, ZoneId zone, List<WeeklyInterval> intervals,
            ScheduleIntervalKind kind, boolean containment) {
        boolean matched = false;
        for (WeeklyInterval interval : intervals) {
            if (interval.kind() != kind) continue;
            for (Occurrence occurrence : occurrences(interval, start, end, zone)) {
                if (!occurrence.valid()) return Match.INVALID;
                boolean current = containment
                        ? !start.isBefore(occurrence.start()) && !end.isAfter(occurrence.end())
                        : occurrence.start().isBefore(end) && start.isBefore(occurrence.end());
                matched |= current;
            }
        }
        return matched ? Match.YES : Match.NO;
    }

    private List<Occurrence> occurrences(WeeklyInterval interval, Instant reference, Instant requestEnd, ZoneId zone) {
        LocalDate local = reference.atZone(zone).toLocalDate();
        LocalDate requestLastDate = requestEnd.minusNanos(1).atZone(zone).toLocalDate();
        LocalDate monday = local.minusDays(local.getDayOfWeek().getValue() - 1L);
        return java.util.stream.LongStream.of(-7, 0, 7)
                .mapToObj(offset -> occurrence(interval, monday.plusDays(offset), zone))
                .filter(candidate -> !candidate.localEnd().toLocalDate().isBefore(local)
                        && !candidate.localStart().toLocalDate().isAfter(requestLastDate))
                .toList();
    }

    private Occurrence occurrence(WeeklyInterval interval, LocalDate monday, ZoneId zone) {
        LocalDateTime start = monday.atStartOfDay().plusMinutes(interval.weekStartMinute());
        LocalDateTime end = monday.atStartOfDay().plusMinutes(interval.weekEndMinute());
        Instant startInstant = strictInstant(start, zone);
        Instant endInstant = strictInstant(end, zone);
        boolean valid = startInstant != null && endInstant != null && startInstant.isBefore(endInstant);
        return new Occurrence(start, end, startInstant, endInstant, valid);
    }

    private Instant strictInstant(LocalDateTime value, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(value);
        return offsets.size() == 1 ? value.toInstant(offsets.getFirst()) : null;
    }

    private boolean hasStrictDayBoundaries(LocalDate first, LocalDate last, ZoneId zone) {
        return first.datesUntil(last.plusDays(1)).allMatch(date ->
                strictInstant(date.atStartOfDay(), zone) != null
                        && strictInstant(date.plusDays(1).atStartOfDay(), zone) != null);
    }

    private enum Match { YES, NO, INVALID }
    private record Occurrence(
            LocalDateTime localStart, LocalDateTime localEnd,
            Instant start, Instant end, boolean valid
    ) { }
}
