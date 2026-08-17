package com.miriyum.domain.reservation.waiting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WaitingReceptionWindowTest {

    private static final Instant START = Instant.parse("2026-08-17T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-17T09:00:00Z");

    @Test
    void recordsImmutableOpenEffect() {
        WaitingReceptionWindow window = WaitingReceptionWindow.opened(
                11L,
                7L,
                "interval-key",
                LocalDate.of(2026, 8, 17),
                START.minusSeconds(3_600),
                END,
                3L,
                START.minusSeconds(3_590));

        assertThat(window.getOpenedByJobId()).isEqualTo(11L);
        assertThat(window.getStoreId()).isEqualTo(7L);
        assertThat(window.getOpenedSettingsVersion()).isEqualTo(3L);
        assertThat(window.accepts(START, 3L)).isTrue();
        assertThat(window.accepts(END, 3L)).isFalse();
        assertThat(window.accepts(START, 4L)).isFalse();
    }

    @Test
    void rejectsInvalidWindowAndVersion() {
        assertThatThrownBy(() -> WaitingReceptionWindow.opened(
                11L,
                7L,
                "interval-key",
                LocalDate.of(2026, 8, 17),
                END,
                START,
                0L,
                START)).isInstanceOf(IllegalArgumentException.class);
    }
}
