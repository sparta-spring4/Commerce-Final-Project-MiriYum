package com.miriyum.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.miriyum.domain.schedule.closure.entity.RegularClosureVersion;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.WeeklyInterval;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreServiceIntervalPolicyTest {
    private final StoreServiceIntervalPolicy policy = new StoreServiceIntervalPolicy();

    @Test void requiresWholeServiceIntervalInsideBusinessAndOutsideBreak() {
        RegularClosureVersion regular = RegularClosureVersion.createDraft(1, 1, "Asia/Seoul", List.of(), List.of());
        regular.activate(Instant.parse("2026-08-01T00:00:00Z"), "게시");
        List<WeeklyInterval> operating = List.of(
                interval(DayOfWeek.MONDAY, 17, 0, 21, 0, ScheduleIntervalKind.BUSINESS_HOURS),
                interval(DayOfWeek.MONDAY, 19, 0, 19, 30, ScheduleIntervalKind.BREAK_TIME));
        List<WeeklyInterval> reservation = List.of(
                interval(DayOfWeek.MONDAY, 18, 0, 20, 0, ScheduleIntervalKind.RESERVATION_SLOT));
        var sources = new StoreServiceIntervalPolicy.Sources(true, "Asia/Seoul", operating, reservation, regular, List.of());

        assertThat(policy.accepts(request("2026-08-03T09:00:00Z", "2026-08-03T09:45:00Z"), sources)).isTrue();
        assertThat(policy.accepts(request("2026-08-03T09:00:00Z", "2026-08-03T10:15:00Z"), sources)).isFalse();
        assertThat(policy.accepts(request("2026-08-03T09:00:00Z", "2026-08-03T12:30:00Z"), sources)).isFalse();
    }

    @Test void missingRegularClosureSourceFailsClosed() {
        var sources = new StoreServiceIntervalPolicy.Sources(true, "Asia/Seoul", List.of(), List.of(), null, List.of());
        assertThat(policy.accepts(request("2026-08-03T09:00:00Z", "2026-08-03T10:00:00Z"), sources)).isFalse();
    }

    @Test void dstGapBreakBoundaryFailsClosedInsteadOfBeingDropped() {
        RegularClosureVersion regular = RegularClosureVersion.createDraft(1, 1, "America/New_York", List.of(), List.of());
        regular.activate(Instant.parse("2026-01-01T00:00:00Z"), "게시");
        List<WeeklyInterval> operating = List.of(
                interval(DayOfWeek.SUNDAY, 0, 0, 5, 0, ScheduleIntervalKind.BUSINESS_HOURS),
                interval(DayOfWeek.SUNDAY, 2, 0, 3, 0, ScheduleIntervalKind.BREAK_TIME));
        List<WeeklyInterval> reservation = List.of(
                interval(DayOfWeek.SUNDAY, 0, 0, 5, 0, ScheduleIntervalKind.RESERVATION_SLOT));
        var sources = new StoreServiceIntervalPolicy.Sources(
                true, "America/New_York", operating, reservation, regular, List.of());

        assertThat(policy.accepts(request("2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z"), sources)).isFalse();
    }

    @Test void dstOverlapBreakBoundaryFailsClosedInsteadOfBeingDropped() {
        RegularClosureVersion regular = RegularClosureVersion.createDraft(1, 1, "America/New_York", List.of(), List.of());
        regular.activate(Instant.parse("2026-01-01T00:00:00Z"), "게시");
        List<WeeklyInterval> operating = List.of(
                interval(DayOfWeek.SUNDAY, 0, 0, 3, 0, ScheduleIntervalKind.BUSINESS_HOURS),
                interval(DayOfWeek.SUNDAY, 1, 0, 2, 0, ScheduleIntervalKind.BREAK_TIME));
        List<WeeklyInterval> reservation = List.of(
                interval(DayOfWeek.SUNDAY, 0, 0, 3, 0, ScheduleIntervalKind.RESERVATION_SLOT));
        var sources = new StoreServiceIntervalPolicy.Sources(
                true, "America/New_York", operating, reservation, regular, List.of());

        assertThat(policy.accepts(request("2026-11-01T04:30:00Z", "2026-11-01T07:30:00Z"), sources)).isFalse();
    }

    @Test void midnightTransitionForRegularDayMaterializationFailsClosed() {
        RegularClosureVersion regular = RegularClosureVersion.createDraft(1, 1, "America/Sao_Paulo", List.of(), List.of());
        regular.activate(Instant.parse("2018-01-01T00:00:00Z"), "게시");
        List<WeeklyInterval> operating = List.of(
                interval(DayOfWeek.SUNDAY, 1, 0, 3, 0, ScheduleIntervalKind.BUSINESS_HOURS));
        List<WeeklyInterval> reservation = List.of(
                interval(DayOfWeek.SUNDAY, 1, 0, 3, 0, ScheduleIntervalKind.RESERVATION_SLOT));
        var sources = new StoreServiceIntervalPolicy.Sources(
                true, "America/Sao_Paulo", operating, reservation, regular, List.of());

        assertThat(policy.accepts(request("2018-11-04T03:30:00Z", "2018-11-04T04:30:00Z"), sources)).isFalse();
    }

    private StoreServiceIntervalRequest request(String start, String end) {
        return new StoreServiceIntervalRequest(1, Instant.parse(start), Instant.parse(end));
    }
    private WeeklyInterval interval(DayOfWeek day, int sh, int sm, int eh, int em, ScheduleIntervalKind kind) {
        int start = (day.getValue() - 1) * 1440 + sh * 60 + sm;
        int end = (day.getValue() - 1) * 1440 + eh * 60 + em;
        return new WeeklyInterval(day, LocalTime.of(sh, sm), LocalTime.of(eh, em), false, kind, start, end);
    }
}
