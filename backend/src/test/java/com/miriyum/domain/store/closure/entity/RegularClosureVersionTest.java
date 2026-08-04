package com.miriyum.domain.store.closure.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.global.exception.ServiceException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegularClosureVersionTest {

    private static final Instant EFFECTIVE_AT = Instant.parse("2026-08-10T00:00:00Z");

    @Test
    void draftKeepsExplicitWeeklyAndDateRulesInStableOrder() {
        RegularClosureVersion version = RegularClosureVersion.createDraft(
                7L,
                3L,
                "Asia/Seoul",
                List.of(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
                List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 25)));

        assertThat(version.getStatus()).isEqualTo(ScheduleVersionStatus.DRAFT);
        assertThat(version.getEntries())
                .extracting(RegularClosureEntry::ruleKey)
                .containsExactly(
                        "WEEKLY:MONDAY",
                        "WEEKLY:SUNDAY",
                        "DATE:2026-09-01",
                        "DATE:2026-12-25");
        assertThat(version.isClosedOn(LocalDate.of(2026, 9, 7))).isTrue();
        assertThat(version.isClosedOn(LocalDate.of(2026, 12, 25))).isTrue();
        assertThat(version.isClosedOn(LocalDate.of(2026, 9, 2))).isFalse();
    }

    @Test
    void emptyDraftExplicitlyRepresentsNoRegularClosures() {
        RegularClosureVersion version = RegularClosureVersion.createDraft(
                7L, 1L, "Asia/Seoul", List.of(), List.of());

        assertThat(version.getEntries()).isEmpty();
        assertThat(version.isClosedOn(LocalDate.of(2026, 8, 3))).isFalse();
    }

    @Test
    void scheduledPublicationCanBeCancelledBackToDraft() {
        RegularClosureVersion version = draft();

        version.schedule(EFFECTIVE_AT, "추석 휴무 게시");
        version.cancelPublication();

        assertThat(version.getStatus()).isEqualTo(ScheduleVersionStatus.DRAFT);
        assertThat(version.getEffectiveAt()).isNull();
        assertThat(version.getChangeReason()).isNull();
    }

    @Test
    void activationRetiresOnlyAnActiveVersion() {
        RegularClosureVersion version = draft();
        version.activate(EFFECTIVE_AT, "즉시 게시");

        version.retire();

        assertThat(version.getStatus()).isEqualTo(ScheduleVersionStatus.RETIRED);
        assertThatThrownBy(version::retire).isInstanceOf(ServiceException.class);
    }

    @Test
    void duplicateRulesAreRejectedInsteadOfSilentlyCollapsed() {
        assertThatThrownBy(() -> RegularClosureVersion.createDraft(
                7L,
                1L,
                "Asia/Seoul",
                List.of(DayOfWeek.MONDAY, DayOfWeek.MONDAY),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RegularClosureVersion draft() {
        return RegularClosureVersion.createDraft(
                7L,
                1L,
                "Asia/Seoul",
                List.of(DayOfWeek.MONDAY),
                List.of());
    }
}
