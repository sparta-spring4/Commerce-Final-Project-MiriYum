package com.miriyum.domain.store.closure.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.closure.model.TemporaryClosureReason;
import com.miriyum.domain.store.closure.model.TemporaryClosureStatus;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TemporaryClosureTest {

    private static final Instant START = Instant.parse("2026-08-03T09:00:00Z");
    private static final Instant END = Instant.parse("2026-08-03T11:00:00Z");

    @Test
    void statusIsDerivedFromCentralTimeAndUsesHalfOpenEnd() {
        TemporaryClosure closure = closure();

        assertThat(closure.statusAt(START.minusSeconds(1)))
                .isEqualTo(TemporaryClosureStatus.SCHEDULED);
        assertThat(closure.statusAt(START)).isEqualTo(TemporaryClosureStatus.ACTIVE);
        assertThat(closure.statusAt(END.minusSeconds(1)))
                .isEqualTo(TemporaryClosureStatus.ACTIVE);
        assertThat(closure.statusAt(END)).isEqualTo(TemporaryClosureStatus.ENDED);
    }

    @Test
    void overlapUsesHalfOpenIntervals() {
        TemporaryClosure closure = closure();

        assertThat(closure.overlaps(START.minusSeconds(60), START)).isFalse();
        assertThat(closure.overlaps(END, END.plusSeconds(60))).isFalse();
        assertThat(closure.overlaps(START, START.plusSeconds(1))).isTrue();
    }

    @Test
    void activeClosureCanChangeItsFutureEnd() {
        TemporaryClosure closure = closure();
        Instant now = START.plusSeconds(60);
        Instant changedEnd = END.plusSeconds(3600);

        closure.changeEndAt(changedEnd, now);

        assertThat(closure.getEndAt()).isEqualTo(changedEnd);
        assertThat(closure.getChangeVersion()).isEqualTo(2L);
    }

    @Test
    void endedOrCancelledClosureCannotBeChanged() {
        TemporaryClosure ended = closure();
        TemporaryClosure cancelled = closure();
        cancelled.cancel(START.minusSeconds(60));

        assertThatThrownBy(() -> ended.changeEndAt(
                END.plusSeconds(1), END.plusSeconds(1)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> cancelled.changeEndAt(
                END.plusSeconds(1), START))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void cancellationDominatesTimeDerivedState() {
        TemporaryClosure closure = closure();

        closure.cancel(START.minusSeconds(60));

        assertThat(closure.statusAt(START)).isEqualTo(TemporaryClosureStatus.CANCELLED);
        assertThatThrownBy(() -> closure.cancel(START))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void activeClosureCannotBeCancelled() {
        TemporaryClosure closure = closure();

        assertThatThrownBy(() -> closure.cancel(START))
                .isInstanceOf(ServiceException.class);
        assertThat(closure.statusAt(START)).isEqualTo(TemporaryClosureStatus.ACTIVE);
    }

    @Test
    void invalidIntervalIsRejected() {
        assertThatThrownBy(() -> TemporaryClosure.create(
                7L,
                START,
                START,
                "Asia/Seoul",
                TemporaryClosureReason.MAINTENANCE,
                "장비 점검"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TemporaryClosure closure() {
        return TemporaryClosure.create(
                7L,
                START,
                END,
                "Asia/Seoul",
                TemporaryClosureReason.MAINTENANCE,
                "장비 점검");
    }
}
