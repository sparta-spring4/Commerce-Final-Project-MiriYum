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
        if (!insideAny(request.startAt(), request.startAt().plusNanos(1), zone,
                sources.reservationIntervals(), ScheduleIntervalKind.RESERVATION_SLOT)) return false;
        if (!insideAny(request.startAt(), request.serviceEndAt(), zone,
                sources.operatingIntervals(), ScheduleIntervalKind.BUSINESS_HOURS)) return false;
        if (overlapsAny(request.startAt(), request.serviceEndAt(), zone,
                sources.operatingIntervals(), ScheduleIntervalKind.BREAK_TIME)) return false;
        LocalDate first = request.startAt().atZone(zone).toLocalDate();
        LocalDate last = request.serviceEndAt().minusNanos(1).atZone(zone).toLocalDate();
        if (first.datesUntil(last.plusDays(1)).anyMatch(sources.regularClosure()::isClosedOn)) return false;
        return sources.temporaryClosures().stream()
                .noneMatch(c -> c.getCancelledAt() == null && c.overlaps(request.startAt(), request.serviceEndAt()));
    }

    private boolean insideAny(Instant start, Instant end, ZoneId zone, List<WeeklyInterval> intervals,
            ScheduleIntervalKind kind) {
        return intervals.stream().filter(i -> i.kind() == kind)
                .anyMatch(i -> occurrences(i, start, zone).stream()
                        .anyMatch(o -> !start.isBefore(o.start) && !end.isAfter(o.end)));
    }

    private boolean overlapsAny(Instant start, Instant end, ZoneId zone, List<WeeklyInterval> intervals,
            ScheduleIntervalKind kind) {
        return intervals.stream().filter(i -> i.kind() == kind)
                .anyMatch(i -> occurrences(i, start, zone).stream()
                        .anyMatch(o -> o.start.isBefore(end) && start.isBefore(o.end)));
    }

    private List<Occurrence> occurrences(WeeklyInterval interval, Instant reference, ZoneId zone) {
        LocalDate local = reference.atZone(zone).toLocalDate();
        LocalDate monday = local.minusDays(local.getDayOfWeek().getValue() - 1L);
        return java.util.stream.LongStream.of(-7, 0, 7)
                .mapToObj(offset -> occurrence(interval, monday.plusDays(offset), zone))
                .filter(java.util.Objects::nonNull).toList();
    }

    private Occurrence occurrence(WeeklyInterval interval, LocalDate monday, ZoneId zone) {
        LocalDateTime start = monday.atStartOfDay().plusMinutes(interval.weekStartMinute());
        LocalDateTime end = monday.atStartOfDay().plusMinutes(interval.weekEndMinute());
        Instant startInstant = strictInstant(start, zone);
        Instant endInstant = strictInstant(end, zone);
        return startInstant == null || endInstant == null || !startInstant.isBefore(endInstant)
                ? null : new Occurrence(startInstant, endInstant);
    }

    private Instant strictInstant(LocalDateTime value, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(value);
        return offsets.size() == 1 ? value.toInstant(offsets.getFirst()) : null;
    }

    private record Occurrence(Instant start, Instant end) { }
}
